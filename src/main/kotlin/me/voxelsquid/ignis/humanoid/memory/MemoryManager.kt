package me.voxelsquid.ignis.humanoid.memory

import me.voxelsquid.ignis.Ignis.Companion.ignisInstance
import org.bukkit.NamespacedKey
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import java.util.*

class MemoryManager {

    data class Memory(
        val opinions: MutableMap<UUID, String> = mutableMapOf(),
        val shortMemory: MutableList<String> = mutableListOf()
    ) {

        fun save(villager: Villager) {
            villager.persistentDataContainer.set(memoryKey, PersistentDataType.STRING, this.toJson())
        }

        fun toJson(): String {
            return plugin.gson.toJson(this)
        }

        companion object {

            fun fromJson(json: String): Memory {
                return plugin.gson.fromJson(json, Memory::class.java)
            }

        }

    }

    companion object {

        val plugin    = ignisInstance
        val memoryKey = NamespacedKey(ignisInstance, "Memory")

        fun Villager.getMemory() : Memory {
            return this.persistentDataContainer.get(memoryKey, PersistentDataType.STRING)?.let {
                Memory.fromJson(it)
            } ?: Memory().also {
                this.persistentDataContainer.set(memoryKey, PersistentDataType.STRING, it.toJson())
            }
        }
    }

}