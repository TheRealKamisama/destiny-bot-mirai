package cn.ac.origind.destinybot

import cn.ac.origind.destinybot.data.DataStore
import cn.ac.origind.destinybot.data.User
import cn.ac.origind.destinybot.data.users
import cn.ac.origind.destinybot.database.getRandomLore
import cn.ac.origind.destinybot.database.searchItemDefinitions
import cn.ac.origind.destinybot.exception.WeaponNotFoundException
import cn.ac.origind.destinybot.exception.joinToString
import cn.ac.origind.destinybot.image.getImage
import cn.ac.origind.destinybot.response.bungie.DestinyMembershipQuery
import io.ktor.client.features.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import net.mamoe.mirai.event.MessagePacketSubscribersBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.buildMessageChain
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

val profileQuerys = ConcurrentHashMap<Long, List<DestinyMembershipQuery>>()

fun MessagePacketSubscribersBuilder.destinyCommands() {
    content(caseAny("/ds help", "/dshelp", "/help").filter).reply {
        buildString {
            appendLine("欢迎使用 LG 的命运2小帮手机器人 555EX780+1GGWP 版。")
            appendLine("获取该帮助: /ds help, /dshelp, /help")
            appendLine("帮助的帮助: 带<>的为必填内容, []为选填内容")
            appendLine("命运2命令:")
            appendLine("“传奇故事” 或 <传奇故事的名称>: 获取一个随机或特定的传奇故事")
            appendLine("perk<武器>: 在 light.gg 上获取武器 Perk 信息")
            appendLine("/ds search <用户名>: 搜索一名命运2玩家")
            appendLine("/tracker <用户名>: 在 Destiny Tracker 上搜索一名玩家")
            appendLine("绑定 <搜索结果前的序号|玩家ID>: 绑定你的命运2账户到QQ号")
            appendLine("我的信息: 若绑定命运2账户则显示玩家信息")
            appendLine()
            appendLine("Minecraft 命令:")
            appendLine("/<MC版本, 去掉.> 如/1710: 显示你在玩的MC版本有多远古")
            appendLine("/latest: 显示最新 Minecraft 快照信息")
            appendLine("/release: 显示最新 Minecraft 信息")
            appendLine("/ping: 显示 Origind 服务器信息")
            appendLine("/ping <原版/origind/gtnhhard/goodtime/咕咕time>: 显示其他的服务器信息")
            appendLine("/ping <服务器地址>: 显示你指定的服务器信息, 暂不支持 SRV 记录")
            appendLine()
            appendLine("WIP: 斗地主功能和UNO功能 未实现")
            append("如有任何问题[想被LG喷一顿] 请@你群中的LG")
        }
    }
    case("传奇故事") {
        val lore = getRandomLore()
        reply("传奇故事：" + lore.name + '\n' + lore.lore)
    }
    case("我的信息") {
        val user = users[sender.id]
        if (user?.destinyMembershipId == null) reply("你还没有绑定账号! 请搜索一个玩家并绑定之。")
        else {
            DestinyBot.replyProfile(user.destinyMembershipType, user.destinyMembershipId!!, this)
        }
    }
    matching(Regex("绑定 (\\d+)")) {
        val content = message[PlainText]?.content!!
        val id = content.removePrefix("绑定 ").toLong()
        if (profileQuerys[sender.id]?.get(id.toInt() - 1) == null) {
            // 直接绑定 ID
            if (content.length < 8) reply("你输入的命运2 ID是不是稍微短了点？")
            else {
                val destinyMembership = getProfile(3, id.toString())?.profile?.data?.userInfo
                if (destinyMembership == null) reply("无法找到该玩家，检查一下？")
                else {
                    users.getOrPut(sender.id) { User(sender.id) }.apply {
                        destinyMembershipId = destinyMembership.membershipId
                        destinyMembershipType = destinyMembership.membershipType
                        destinyDisplayName = destinyMembership.displayName
                    }
                    DataStore.save()
                    reply("绑定 ${destinyMembership.displayName}(${destinyMembership.membershipId}) 到 ${sender.id} 成功。")
                }
            }
        } else {
            // 绑定搜索序号
            val result = profileQuerys[sender.id]!!
            val index = id - 1
            if (result.size < index + 1) reply("你的序号太大了点。")
            val destinyMembership = result[index.toInt()]
            try {
                users.getOrPut(sender.id) { User(sender.id) }.apply {
                    destinyMembershipId = destinyMembership.membershipId
                    destinyMembershipType = destinyMembership.membershipType
                    destinyDisplayName = destinyMembership.displayName
                }
                DataStore.save()
                reply("绑定 ${destinyMembership.displayName}(${destinyMembership.membershipId}) 到 ${sender.id} 成功。")
            } catch (e: ServerResponseException) {
                reply("获取详细信息时失败，请重试。\n${e.localizedMessage}")
            }
        }
    }

    content(caseAny("周报", "命运周报").filter).reply {
        buildMessageChain {
            add(getImage("https:${getLatestWeeklyReportURL()}").upload())
        }
    }

    matching(Regex("\\d+")) {
        if (profileQuerys[sender.id].isNullOrEmpty())
            return@matching
        val packet = this
        GlobalScope.launch {
            val result = profileQuerys[packet.sender.id]!!
            val index = packet.message[PlainText]?.content!!.toInt() - 1
            if (result.size < index + 1) return@launch
            val destinyMembership = result[index]
            try {
                DestinyBot.replyProfile(
                    destinyMembership.membershipType,
                    destinyMembership.membershipId,
                    packet
                )
            } catch (e: ServerResponseException) {
                packet.reply("获取详细信息时失败，请重试。\n${e.localizedMessage}")
            }
        }
    }
    startsWith("perk") {
        if (it.isBlank()) return@startsWith
        for (item in searchItemDefinitions(it)) {
            GlobalScope.launch(Dispatchers.Default) {
                try {
                    val perks = getItemPerks(item._id!!)
                    DestinyBot.replyPerks(item, perks, this@startsWith)
                } catch (e: WeaponNotFoundException) {
                    reply(e.message ?: "")
                } catch (e: ItemNotFoundException) {
                    reply("搜索失败: ${e.localizedMessage}, 正在尝试其他方式")
                } catch (e: Exception) {
                    reply("搜索失败：" + e.joinToString())
                }
            }
        }
    }
    matching(Regex("/ds \\d+")) {
        DestinyBot.replyProfile(3, message[PlainText]!!.content.removePrefix("/ds "), this)
    }
    startsWith("/ds search ") {
        val packet = this
        profileQuerys.remove(packet.sender.id)
        GlobalScope.launch {
            val criteria = packet.message[PlainText]!!.content.removePrefix("/ds search ")
            val result =
                withContext(Dispatchers.Default) { searchUsersInternal(criteria) }
            val profiles =
                withContext(Dispatchers.Default) { searchProfiles(criteria) }
            packet.reply("搜索命运2玩家: $criteria")
            if (result.isNullOrEmpty() && profiles.isNullOrEmpty()) {
                packet.reply("没有搜索到玩家，请检查你的搜索内容")
                return@launch
            }

            // Filter Destiny 2 players
            val players = mutableSetOf<DestinyMembershipQuery>()
            players.addAll(profiles ?: emptyList())
            result.map { profile ->
                launch {
                    try {
                        val destinyMembership = DestinyBot.bungieUserToDestinyUser(profile.membershipId)
                        if (destinyMembership != null) {
                            players.add(destinyMembership)
                        }
                    } catch (e: ConnectTimeoutException) {
                        packet.reply("尝试获取玩家 ${profile.steamDisplayName ?: profile.displayName} 信息时超时。")
                    }
                }
            }.joinAll()
            profileQuerys[packet.sender.id] = players.toList()
            packet.reply(buildString {
                appendLine("搜索到玩家: ")
                players.forEachIndexed { index, profile ->
                    appendLine("${index + 1}. ${profile.displayName}: ...${profile.membershipId.takeLast(3)}")
                }
                appendLine("请直接回复前面的序号来获取详细信息。")
                appendLine("或者，回复 绑定 [序号] 来将该用户绑定到你的 QQ 上。")
            })
        }
    }
}
