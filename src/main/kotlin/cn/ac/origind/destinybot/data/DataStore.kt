package cn.ac.origind.destinybot.data

import cn.ac.origind.destinybot.moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

val users = hashMapOf<Long, User>()
val userAdapter = moshi.adapter(User::class.java)

object DataStore {
    private val writeOptions = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    val logger = LoggerFactory.getLogger("DataStore")
    val usersDir = Paths.get("users").toAbsolutePath()

    suspend fun init() = withContext(Dispatchers.IO) {
        if (Files.notExists(usersDir)) {
            Files.createDirectories(usersDir)
            logger.info("Created users directory {}", usersDir.toString())
        }

        load()
    }

    suspend fun save() {
        withContext(Dispatchers.IO) {
            for ((id, user) in users) {
                Files.write(
                    usersDir.resolve("$id.json"),
                    userAdapter.toJson(user).toByteArray(StandardCharsets.UTF_8),
                    *writeOptions
                )
            }
            logger.info("Saved {} users", users.size)
        }
    }

    suspend fun load() {
        users.clear()
        withContext(Dispatchers.IO) {
            Files.list(usersDir).forEach {
                val user = userAdapter.fromJson(String(Files.readAllBytes(it), StandardCharsets.UTF_8))!!
                users[user.qq] = user
            }
            logger.info("Loaded {} users", users.size)
        }
    }

    suspend fun reload() {
        save()
        load()
    }
}