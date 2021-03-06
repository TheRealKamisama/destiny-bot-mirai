package cn.ac.origind.destinybot

import cn.ac.origind.destinybot.response.lightgg.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.litote.kmongo.findOne
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ItemNotFoundException(itemId: String, displayName: String? = null) : Exception("未在 light.gg 上找到物品 $itemId")

suspend fun getItemPerks(item: ItemDefinition) = getItemPerks(item._id!!)

suspend fun getItemPerks(itemId: String): ItemPerks = withContext(Dispatchers.IO) {
    val dir = Paths.get("destiny2_perks")
    val path = dir.resolve("$itemId.json")

    if (Files.notExists(dir)) Files.createDirectories(dir)
    if (Files.exists(path)) {
        return@withContext moshi.adapter(Item::class.java).fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8))?.perks!!
    } else {
        val perks = getItemPerksInternal(itemId)
        val itemDefJson = DestinyBot.db.getCollection("DestinyInventoryItemDefinition_chs").findOne("""{"hash": $itemId}""")?.toJson()
        Files.write(path, moshi.adapter(Item::class.java).toJson(Item(perks, moshi.adapter(ItemDefinition::class.java).fromJson(itemDefJson))).toByteArray(StandardCharsets.UTF_8))
        return@withContext perks
    }
}

suspend fun getItemPerksInternal(itemId: String): ItemPerks {
    suspend fun request(): String {
        return getBody("https://www.light.gg/db/items/$itemId")
    }

    val regex = Regex("<div class=\"clearfix perks\">(.+)<div id=\"my-rolls\">", RegexOption.DOT_MATCHES_ALL)



    val text = regex.find(request())?.groupValues?.get(0)!!
    val iconRegex = Regex("src=\"https://bungie.net(/common/destiny2_content/icons/(\\w+).png)")
    val hashRegex = Regex("<a href=\"/db/items/(\\d+)/")

    // MongoDB
    val perksCollection = DestinyBot.db.getCollection("DestinySandboxPerkDefinition_chs")
    val itemsCollection = DestinyBot.db.getCollection("DestinyInventoryItemDefinition_chs")

    val perks = ItemPerks()
    val pvp = mutableListOf<ItemPerk>()
    val pve = mutableListOf<ItemPerk>()
    val favorite = mutableListOf<ItemPerk>()
    val normal = mutableListOf<ItemPerk>()

    fun getPerk(hash: String): ItemPerk? {
        val perkJson = perksCollection.findOne("""{"hash": $hash}""")
            ?.toJson()
        val itemJson = itemsCollection.findOne("""{"hash": $hash}""")
            ?.toJson()
        val perk = moshi.adapter(ItemPerk::class.java).fromJson(itemJson ?: perkJson)
        if (perk != null) {
            perk.type = when (perk.itemTypeDisplayName) {
                "枪管" -> PerkType.BARREL
                "弹匣" -> PerkType.MAGAZINE
                else -> null
            }
        }
        return perk
    }

    // Curated rolls
    val curatedText = if (text.contains("Random Rolls"))
        Regex(
            "Not all curated rolls actually drop in-game.(.+)Random Rolls",
            RegexOption.DOT_MATCHES_ALL
        ).find(text)?.groups?.get(0)?.value else text
    if (curatedText != null) {
        perks.curated = curatedText.split("list-unstyled sockets").mapNotNull {
            // Curated rolls
            hashRegex.find(it)?.groups?.get(1)?.value?.let { it1 -> getPerk(it1) }
        }.filter { it.displayProperties?.name?.contains("皮肤") == false }
    }
    val randomText = text.split("Hide Recommendations").getOrNull(1)
    if (randomText != null) {
        randomText.split("<ul class=\"list-unstyled\">").takeLast(4).forEachIndexed { index, str ->
            str.split("<li").forEach {
                val perk = hashRegex.find(it)?.groupValues?.get(1)?.let { it1 -> getPerk(it1) }
                perk?.type = PerkType.values()[index]
                when {
                    it.contains("prefpvp") -> {
                        // PvP Perk
                        perk?.let { it1 -> pvp += it1; it1.perkRecommend = 1 }
                    }
                    it.contains("prefpve") -> {
                        // PvE perk
                        perk?.let { it1 -> pve += it1; it1.perkRecommend = 0 }
                    }
                    it.contains("pref") -> {
                        // Community Favorite
                        perk?.let { it1 -> favorite += it1; it1.perkRecommend = 2 }
                    }
                    else -> {
                        // Rubbish
                        perk?.let { it1 -> normal += it1; it1.perkRecommend = -1 }
                    }
                }
            }
        }
    } else perks.onlyCurated = true
    perks.pvp = pvp
    perks.pve = pve
    perks.favorite = favorite
    perks.normal = normal
    return perks
}
