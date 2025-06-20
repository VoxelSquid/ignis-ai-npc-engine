package me.voxelsquid.anima.humanoid

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.takeItemFromQuillInventory
import me.voxelsquid.psyche.HumanoidController.Companion.asHumanoid
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.Material.*
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.Damageable
import kotlin.random.Random

class EquipmentManager : Listener {

    private val plugin = ignisInstance

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (plugin.allowedWorlds.contains(event.world)) {
            event.world.entities.filterIsInstance<Villager>().forEach {
                this.removeEquipment(it)
                this.equip(it)
            }
        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        if (plugin.allowedWorlds.contains(event.world)) {
            event.chunk.entities.filterIsInstance<Villager>().forEach {
                this.removeEquipment(it)
                this.equip(it)
            }
        }
    }

    @EventHandler
    private fun onEntityResurrect(event: EntityResurrectEvent) {
        if (plugin.allowedWorlds.contains(event.entity.world)) {
            (event.entity as? Villager)?.let { villager ->
                event.hand?.let { slot ->
                    villager.asHumanoid()?.equip(slot, ItemStack(AIR))
                    villager.takeItemFromQuillInventory(ItemStack(TOTEM_OF_UNDYING), 1)
                }
            }
        }
    }

    fun tick() {
        plugin.allowedWorlds.forEach { world ->
            world.entities.filterIsInstance<Villager>().forEach(this::equip)
        }
    }

    private fun removeEquipment(villager: Villager) {
        EquipmentSlot.entries.forEach { slot ->
            villager.equipment?.clear()
        }
    }

    private fun equip(villager: Villager) {
        val changes = mutableMapOf<EquipmentSlot, ItemStack>()

        // Фильтруем непустые предметы из саб-инвентаря
        val availableItems = villager.subInventory.filterNotNull()

        // Проходим по каждому слоту
        for (slot in EquipmentSlot.entries) {

            // Собираем все подходящие предметы для слота
            val possibleItems = availableItems
                .filter { this.getSlotForItem(it) == slot }

            if (possibleItems.isEmpty()) continue

            // Сортируем по оценке
            val bestItem = if (possibleItems.size == 1) {
                possibleItems.first()
            } else possibleItems.maxByOrNull { evaluateItem(it, slot) }!!

            changes[slot] = bestItem
        }

        // Применяем изменения
        this.applyEquipmentChanges(villager, changes)
    }

    // Определяем подходящий слот для предмета
    private fun getSlotForItem(item: ItemStack): EquipmentSlot? {
        return when (item.type) {

            WOODEN_SWORD, STONE_SWORD, IRON_SWORD,
            GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD,
            WOODEN_AXE, STONE_AXE, IRON_AXE,
            DIAMOND_AXE, NETHERITE_AXE -> EquipmentSlot.HAND

            SHIELD, TOTEM_OF_UNDYING -> EquipmentSlot.OFF_HAND

            LEATHER_HELMET, CHAINMAIL_HELMET, IRON_HELMET,
            GOLDEN_HELMET, DIAMOND_HELMET, NETHERITE_HELMET,
            TURTLE_HELMET -> EquipmentSlot.HEAD

            LEATHER_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_CHESTPLATE,
            GOLDEN_CHESTPLATE, DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> EquipmentSlot.CHEST

            LEATHER_LEGGINGS, CHAINMAIL_LEGGINGS, IRON_LEGGINGS,
            GOLDEN_LEGGINGS, DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> EquipmentSlot.LEGS

            LEATHER_BOOTS, CHAINMAIL_BOOTS, IRON_BOOTS,
            GOLDEN_BOOTS, DIAMOND_BOOTS, NETHERITE_BOOTS -> EquipmentSlot.FEET

            else -> null
        }
    }

    // Оценка качества предмета
    private fun evaluateItem(item: ItemStack?, slot: EquipmentSlot): Double {
        if (item == null || this.getSlotForItem(item) != slot) return 0.0

        var score = 0.0

        // Оценка по материалу
        score += when (item.type) {
            in listOf(WOODEN_SWORD, WOODEN_AXE) -> 1.0
            in listOf(STONE_SWORD, STONE_AXE) -> 2.0
            in listOf(IRON_SWORD, IRON_AXE, IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS) -> 3.0
            in listOf(GOLDEN_SWORD, GOLDEN_AXE, GOLDEN_HELMET, GOLDEN_CHESTPLATE, GOLDEN_LEGGINGS, GOLDEN_BOOTS) -> 2.5
            in listOf(DIAMOND_SWORD, DIAMOND_AXE, DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS) -> 4.0
            in listOf(NETHERITE_SWORD, NETHERITE_AXE, NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS) -> 5.0
            SHIELD -> 2.0
            TOTEM_OF_UNDYING -> 3.0
            TURTLE_HELMET -> 3.5
            LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> 1.5
            CHAINMAIL_HELMET, CHAINMAIL_CHESTPLATE, CHAINMAIL_LEGGINGS, CHAINMAIL_BOOTS -> 2.5
            else -> 0.0
        }

        // Оценка по зачарованиям
        item.enchantments.forEach { (enchant, level) ->
            score += when (enchant) {
                Enchantment.SHARPNESS, Enchantment.PROTECTION -> level * 1.5
                Enchantment.UNBREAKING -> level * 0.5
                else -> level * 0.3
            }
        }

        (item.itemMeta as? Damageable)?.let { meta ->
            val durability = (item.type.maxDurability - meta.damage).toDouble() / item.type.maxDurability
            score -= durability * 0.5 // Штраф за износ
        }

        return score
    }

    // Применяем изменения экипировки
    private fun applyEquipmentChanges(villager: Villager, changes: Map<EquipmentSlot, ItemStack>) {
        for ((slot, item) in changes) {

            val equipped = villager.equipment?.getItem(slot)

            // We're not equipping the same items.
            if (equipped?.isSimilar(item) == true)
                continue

            villager.asHumanoid()?.equip(slot, item)

            val sound = when (item.type) {
                IRON_BOOTS, IRON_LEGGINGS, IRON_CHESTPLATE, IRON_HELMET -> Sound.ITEM_ARMOR_EQUIP_IRON
                CHAINMAIL_BOOTS, CHAINMAIL_LEGGINGS, CHAINMAIL_CHESTPLATE, CHAINMAIL_HELMET -> Sound.ITEM_ARMOR_EQUIP_CHAIN
                GOLDEN_BOOTS, GOLDEN_LEGGINGS, GOLDEN_CHESTPLATE, GOLDEN_HELMET -> Sound.ITEM_ARMOR_EQUIP_GOLD
                DIAMOND_BOOTS, DIAMOND_LEGGINGS, DIAMOND_CHESTPLATE, DIAMOND_HELMET -> Sound.ITEM_ARMOR_EQUIP_DIAMOND
                NETHERITE_BOOTS, NETHERITE_LEGGINGS, NETHERITE_CHESTPLATE, NETHERITE_HELMET -> Sound.ITEM_ARMOR_EQUIP_NETHERITE
                SHIELD -> Sound.ITEM_SHIELD_BLOCK
                IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
                else -> Sound.ENTITY_ITEM_PICKUP
            }

            villager.world.playSound(villager.location, sound, 0.85F, 1F + Random.nextDouble(-0.25, 0.25).toFloat())
        }
    }
}