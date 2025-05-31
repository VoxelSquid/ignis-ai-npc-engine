package me.voxelsquid.anima.humanoid

import me.voxelsquid.anima.Anima.Companion.ignisInstance
import org.bukkit.NamespacedKey

object HumanoidNamespace {
    val voiceKey        = NamespacedKey(ignisInstance, "VoiceSound")
    val pitchKey        = NamespacedKey(ignisInstance, "VoicePitch")
    val skinKey         = NamespacedKey(ignisInstance, "Skin")
    val questDataKey    = NamespacedKey(ignisInstance, "NPCQuestData")
    val hungerKey       = NamespacedKey(ignisInstance, "Hunger")
    val settlementKey   = NamespacedKey(ignisInstance, "Settlement")
    val inventoryKey    = NamespacedKey(ignisInstance, "Inventory")
    val memoryKey       = NamespacedKey(ignisInstance, "Memory")
    val spawnerKey      = NamespacedKey(ignisInstance, "SpawnerSpawned")
}