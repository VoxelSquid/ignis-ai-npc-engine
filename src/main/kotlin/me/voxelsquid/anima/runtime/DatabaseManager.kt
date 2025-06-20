package me.voxelsquid.anima.runtime

import com.cryptomorin.xseries.XItemStack
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger
import kotlin.random.Random

class DatabaseManager(plugin: JavaPlugin) {
    private val logger: Logger = plugin.logger
    private val dbFile: File = File(plugin.dataFolder, "data.db")
    private var connection: Connection? = null
    private val gson = Gson()

    init {
        // Ensure plugin data folder exists
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        // Initialize database connection
        initializeDatabase()
    }

    private fun initializeDatabase() {
        try {
            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC")

            // Connect to database
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            createTables()
        } catch (e: SQLException) {
            logger.severe("Failed to connect to SQLite database: ${e.message}")
        } catch (e: ClassNotFoundException) {
            logger.severe("SQLite JDBC driver not found: ${e.message}")
        }
    }

    private fun createTables() {
        val sql = """
            CREATE TABLE IF NOT EXISTS quest_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_type TEXT NOT NULL,
                item_json TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                drop_chance DOUBLE NOT NULL,
                score INTEGER NOT NULL
            )
        """.trimIndent()

        try {
            connection?.createStatement()?.executeUpdate(sql)
        } catch (e: SQLException) {
            logger.severe("Failed to create table quest_items: ${e.message}")
        }
    }

    fun saveQuestItem(entityType: String, item: ItemStack, quantity: Int, dropChance: Double, score: Int): Boolean {
        val sql = """
            INSERT INTO quest_items (entity_type, item_json, quantity, drop_chance, score)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()

        try {
            val itemMap = XItemStack.serialize(item)
            val json = gson.toJson(itemMap)
            val statement = connection?.prepareStatement(sql)
            statement?.setString(1, entityType)
            statement?.setString(2, json)
            statement?.setInt(3, quantity)
            statement?.setDouble(4, dropChance)
            statement?.setInt(5, score)
            statement?.executeUpdate()
            // TODO ignisInstance.questManager.questItemData.add(QuestItem(entity God knows what this is supposed to be
            return true
        } catch (e: SQLException) {
            logger.severe("Failed to save quest item: ${e.message}")
            return false
        }
    }

    data class QuestItem(
        val id: Int,
        val entityType: String,
        val item: Map<String, Any>,
        val quantity: Int,
        val dropChance: Double,
        val score: Int
    ) {
        constructor(entityType: String, item: Map<String, Any>, quantity: Int, dropChance: Double, score: Int) :
                this(Random.nextInt(), entityType, item, quantity, dropChance, score)

        companion object {
            val EMPTY = QuestItem(0, EntityType.UNKNOWN.toString(), XItemStack.serialize(ItemStack(Material.AIR)), 0, 0.0, 0)
        }
    }

    fun getQuestItemsByEntityType(entityType: EntityType): List<QuestItem> {
        val sql = "SELECT * FROM quest_items WHERE entity_type = ?"
        val items = mutableListOf<QuestItem>()

        try {
            val statement = connection?.prepareStatement(sql)
            statement?.setString(1, entityType.toString())
            val resultSet = statement?.executeQuery()

            while (resultSet?.next() == true) {
                val json = resultSet.getString("item_json")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val itemStack: Map<String, Any> = gson.fromJson(json, type)
                items.add(
                    QuestItem(
                        id = resultSet.getInt("id"),
                        entityType = resultSet.getString("entity_type"),
                        item = itemStack,
                        quantity = resultSet.getInt("quantity"),
                        dropChance = resultSet.getDouble("drop_chance"),
                        score = resultSet.getInt("score")
                    )
                )
            }
        } catch (e: SQLException) {
            logger.severe("Failed to retrieve quest items: ${e.message}")
        }

        return items
    }

    fun getAllQuestItems(): MutableList<QuestItem> {
        val sql = "SELECT * FROM quest_items"
        val items = mutableListOf<QuestItem>()

        try {
            val statement = connection?.createStatement()
            val resultSet = statement?.executeQuery(sql)

            while (resultSet?.next() == true) {
                val json = resultSet.getString("item_json")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val itemStack: Map<String, Any> = gson.fromJson(json, type)
                items.add(
                    QuestItem(
                        id = resultSet.getInt("id"),
                        entityType = resultSet.getString("entity_type"),
                        item = itemStack,
                        quantity = resultSet.getInt("quantity"),
                        dropChance = resultSet.getDouble("drop_chance"),
                        score = resultSet.getInt("score")
                    )
                )
            }
        } catch (e: SQLException) {
            logger.severe("Failed to retrieve all quest items: ${e.message}")
        }

        return items
    }

    fun deleteQuestItem(id: Int): Boolean {
        val sql = "DELETE FROM quest_items WHERE id = ?"

        try {
            val statement = connection?.prepareStatement(sql)
            statement?.setInt(1, id)
            val rowsAffected = statement?.executeUpdate() ?: 0
            return rowsAffected > 0
        } catch (e: SQLException) {
            logger.severe("Failed to delete quest item by ID: ${e.message}")
            return false
        }
    }

    fun deleteQuestItem(entityType: String): Int {
        val sql = "DELETE FROM quest_items WHERE entity_type = ?"

        try {
            val statement = connection?.prepareStatement(sql)
            statement?.setString(1, entityType)
            val rowsAffected = statement?.executeUpdate() ?: 0
            return rowsAffected
        } catch (e: SQLException) {
            logger.severe("Failed to delete quest items by entity type: ${e.message}")
            return 0
        }
    }

    fun closeConnection() {
        try {
            connection?.close()
            logger.info("Database connection closed")
        } catch (e: SQLException) {
            logger.severe("Failed to close database connection: ${e.message}")
        }
    }
}