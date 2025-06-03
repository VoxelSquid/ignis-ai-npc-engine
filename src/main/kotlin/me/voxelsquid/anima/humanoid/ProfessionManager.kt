package me.voxelsquid.anima.humanoid

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.replaceMap
import me.voxelsquid.anima.event.VillagerProduceItemEvent
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addItemToQuillInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.professionLevelName
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.takeItemFromQuillInventory
import me.voxelsquid.anima.quest.QuestingUtility
import me.voxelsquid.anima.utility.HexColorLib.color
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.gender
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.*
import org.bukkit.inventory.RecipeChoice.MaterialChoice
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionType
import java.util.*
import kotlin.random.Random

class ProfessionManager: Listener {

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun produceProfessionItem() {

        val villagers = mutableListOf<Villager>()
        plugin.allowedWorlds.forEach { world ->
            villagers.addAll(world.entities.filterIsInstance<Villager>().filter { it.profession != Villager.Profession.NONE && it.pose != Pose.SLEEPING })
        }

        if (villagers.isEmpty())
            return

        // Мы не хотим отправлять много запросов нейронке за раз, поэтому создаём уникальные предметы постепенно.
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            uniqueItemProduceQueue.keys.randomOrNull()?.let { villager ->
                uniqueItemProduceQueue[villager]?.let { uniqueItem ->
                    this.generateUniqueItemDescription(villager, uniqueItem)
                    uniqueItemProduceQueue.remove(villager)
                }
            }
        }, 0, 400)

        plugin.server.scheduler.runTaskAsynchronously(plugin) { _ ->

            for (villager in villagers) {

                val profession = villager.profession

                if (!plugin.configManager.professions.contains("villager-item-producing.profession.$profession"))
                    continue

                // The cleric is always brewing potions.
                if (profession == Villager.Profession.CLERIC) {

                    if (villager.subInventory.filterNotNull().count { it.type == Material.POTION } >= 1 * villager.villagerLevel + 1) {
                        continue
                    }

                    val ingredients = plugin.configManager.professions.getStringList("villager-item-producing.profession.CLERIC.item-priority").map { it.split("~")[0] }
                    villager.subInventory.filterNotNull().find { ingredients.contains(it.type.toString()) }?.let { brewingIngredient ->
                        villager.takeItemFromQuillInventory(brewingIngredient, 1 + Random.nextInt(5))

                        val potion = ItemStack(Material.POTION)
                        val meta = potion.itemMeta as PotionMeta
                        meta.basePotionType = PotionType.entries.random()
                        potion.itemMeta = meta

                        plugin.logger.info("Brewing a potion. Villager: ${villager.customName}, potion is ${meta.basePotionType}.")
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            plugin.server.pluginManager.callEvent(VillagerProduceItemEvent(villager, potion))
                            villager.world.playSound(villager, Sound.ENTITY_VILLAGER_WORK_CLERIC, 1F, 1F)
                        }
                    }
                    continue
                }

                val itemsToProduce = plugin.configManager.professions.getStringList("villager-item-producing.profession.$profession.item-produce")
                if (itemsToProduce.isEmpty()) {
                    continue
                }

                for (item in itemsToProduce.shuffled()) {

                    var itemToProduce = item

                    // Smart tag parsing
                    if (itemToProduce.contains('@')) {
                        itemToProduce = Material.entries.filter { material: Material -> material.isItem && material.toString().contains(itemToProduce.removePrefix("@")) }.random().toString()
                    }

                    val recipes = Bukkit.getRecipesFor(ItemStack(Material.valueOf(itemToProduce)))

                    if (recipes.isEmpty()) {
                        continue
                    }

                    val recipeIngredients = mutableListOf<Material>()
                    val recipe = recipes.random()

                    when (recipe) {

                        is ShapedRecipe -> (recipe.choiceMap.values.filterIsInstance<MaterialChoice>().forEach { ingredient ->
                            recipeIngredients.add(ingredient.itemStack.type)
                        })

                        is ShapelessRecipe -> (recipe.choiceList.filterIsInstance<MaterialChoice>().forEach { ingredient ->
                            recipeIngredients.add(ingredient.itemStack.type)
                        })

                        is FurnaceRecipe -> {
                            try {
                                recipeIngredients.add((recipe.inputChoice as MaterialChoice).itemStack.type)
                            } catch (ignored: ClassCastException) {}
                        }

                        else -> {
                            continue
                        }
                    }

                    // Check if the recipe can be crafted; if not, simply return
                    var canBeCrafted = true
                    for (material in recipeIngredients) {
                        if (!villager.subInventory.contains(material, recipeIngredients.count { ingredient -> ingredient == material })) {
                            canBeCrafted = false
                            break
                        }
                    }

                    if (!canBeCrafted) {
                        continue
                    }

                    // Take the crafting ingredients from the villager's inventory
                    recipeIngredients.forEach { material ->
                        villager.subInventory.filterNotNull().find { it.type == material }?.let {
                            villager.takeItemFromQuillInventory(it, 1)
                        }
                    }

                    val producedItem = recipe.result

                    // Firing event
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        plugin.server.pluginManager.callEvent(VillagerProduceItemEvent(villager, producedItem))
                    }

                    break
                }
            }
        }
    }

    private val uniqueItemProduceQueue = mutableMapOf<Villager, ItemStack>()

    @EventHandler
    private fun onVillagerProduceItem(event: VillagerProduceItemEvent) {

        val villager        = event.villager
        val professionLevel = villager.villagerLevel
        val item            = event.producedItem

        // Unique item generation
        if (plugin.configManager.professions.getStringList("villager-item-producing.mastery-affected-items").contains(item.type.toString())) {
            if (Random.nextInt(100) in 0..professionLevel * plugin.configManager.professions.getInt("villager-item-producing.unique-item-chance")) {
                val uniqueItem = this.createUniqueItem(villager, item)
                uniqueItemProduceQueue[villager] = uniqueItem
                return
            }
        }

        // TODO: Apply random enchantment on the book with a low chance. Librarian sometimes must trade e-books.
        if (item.type == Material.BOOK && villager.profession == Villager.Profession.LIBRARIAN) {

        }

        // Add the result to the inventory
        villager.addItemToQuillInventory(item)
    }

    data class UniqueItemDescription(val itemDescription: String, val itemNames: List<String>)
    fun generateUniqueItemDescription(villager: Villager, item: ItemStack) {

        val villagerName = villager.customName ?: "unknown"
        val settlementName = villager.settlement?.data?.settlementName ?: "no settlement"
        val settlementLevel = villager.settlement?.size().toString()

        val race = villager.race
        val raceName = race.name

        val placeholders = mutableMapOf(
            "villagerGender"          to villager.gender.toString(),
            "villagerName"            to villagerName,
            "villagerType"            to "${villager.villagerType}",
            "villagerRace"            to raceName,
            "villagerProfession"      to "${villager.profession}",
            "villagerProfessionLevel" to villager.professionLevelName,
            "itemType"                to item.type.toString(),
            "extraItemAttributes"     to item.getUniqueItemAttributes(),
            "itemRarity"              to if (item.isUniqueItem()) item.getUniqueItemRarity().toString().lowercase() else UniqueItemRarity.COMMON.toString().lowercase(),
            "settlementName"          to settlementName,
            "settlementLevel"         to settlementLevel,
            "setting"                 to plugin.bifrost.setting,
            "namingStyle"             to plugin.bifrost.namingStyle
        )

        val promptTemplate = plugin.configManager.prompts.getString("unique-item-description")
            ?: throw IllegalArgumentException("Unique item description is not defined! Check prompts.yml!")

        val prompt = promptTemplate.replaceMap(placeholders)
        plugin.bifrost.client.sendRequest(prompt, UniqueItemDescription::class, onSuccess = { data ->
            plugin.logger.info(data.toString())
            plugin.server.scheduler.runTask(plugin) { _ ->
                this.finalizeUniqueItem(villager, item, data)
            }
        }, {})
    }

    private fun finalizeUniqueItem(villager: Villager, item: ItemStack, data: UniqueItemDescription) {

        val meta     = item.itemMeta ?: return
        val rarity   = item.getUniqueItemRarity()

        // Обновляем мету
        meta.setItemName((rarity.color + data.itemNames.random()).color())

        val words = data.itemDescription.split(" ") // spacing
        val lore  = mutableListOf<String>()
        var line  = "§7"

        words.forEach { word ->
            line += "$word "
            if (line.length >= 60) {
                lore.add(line)
                line = "§7"
            }
        }

        if (line != "§7") {
            lore.add(line)
        }

        meta.lore = lore
        item.itemMeta = meta

        // Добавляем итем
        villager.addItemToQuillInventory(item)
    }

    private fun createUniqueItem(villager: Villager, itemStack: ItemStack): ItemStack {

        // Хитро и просто роллим роллы
        var rolls = 0; do ++rolls while (Random.nextInt(100) in 0..50 / rolls + villager.villagerLevel)
        val rarity = rolls

        // Определяем возможные атрибуты для предмета
        val attributeNames: List<String> = when (itemStack.type) {
            Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.swords")
            Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.pickaxes")
            Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.axes")
            Material.LEATHER_HELMET, Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.helmets")
            Material.LEATHER_CHESTPLATE, Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.chestplates")
            Material.LEATHER_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.leggings")
            Material.LEATHER_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.boots")
            else -> plugin.configManager.professions.getStringList("villager-item-producing.allowed-attributes.fishing-rod")
        }

        // Определяем слот
        val slot = when(itemStack.type) {
            Material.LEATHER_HELMET, Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET -> EquipmentSlotGroup.HEAD
            Material.LEATHER_CHESTPLATE, Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE -> EquipmentSlotGroup.CHEST
            Material.LEATHER_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS -> EquipmentSlotGroup.LEGS
            Material.LEATHER_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS -> EquipmentSlotGroup.FEET
            else -> EquipmentSlotGroup.MAINHAND
        }

        val meta = itemStack.itemMeta
        val attributes: List<Attribute> = Registry.ATTRIBUTE.toMutableList().filter { attributeNames.contains(it.key.key.uppercase()) }

        // Чистим дефолтные атрибуты чтобы не было конфликтов
        attributes.forEach { meta?.removeAttributeModifier(it) }

        // Так как при использовании кастомных атрибутов дефолтные сбрасываются, придётся подсчитывать самостоятельно
        var attackSpeed = -4 + when(itemStack.type) {
            Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD -> 1.6
            Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE -> 1.2
            Material.IRON_AXE -> 0.9
            Material.DIAMOND_AXE, Material.NETHERITE_AXE -> 1.0
            else -> 4.0
        }

        var attackDamage: Double = when(itemStack.type) {
            Material.IRON_SWORD -> 6.0
            Material.DIAMOND_SWORD -> 7.0
            Material.NETHERITE_SWORD -> 8.0
            Material.IRON_PICKAXE -> 4.0
            Material.DIAMOND_PICKAXE -> 5.0
            Material.NETHERITE_PICKAXE -> 6.0
            Material.IRON_AXE -> 9.0
            Material.DIAMOND_AXE -> 9.0
            Material.NETHERITE_AXE -> 10.0
            else -> 1.0
        }

        var armorDefence = when (itemStack.type) {
            Material.LEATHER_HELMET -> 1.0
            Material.LEATHER_CHESTPLATE -> 3.0
            Material.LEATHER_LEGGINGS -> 2.0
            Material.LEATHER_BOOTS -> 1.0
            Material.IRON_HELMET -> 1.0
            Material.IRON_CHESTPLATE -> 6.0
            Material.IRON_LEGGINGS -> 5.0
            Material.IRON_BOOTS -> 2.0
            Material.DIAMOND_HELMET -> 3.0
            Material.DIAMOND_CHESTPLATE -> 8.0
            Material.DIAMOND_LEGGINGS -> 6.0
            Material.DIAMOND_BOOTS -> 3.0
            Material.NETHERITE_HELMET -> 3.0
            Material.NETHERITE_CHESTPLATE -> 8.0
            Material.NETHERITE_LEGGINGS -> 6.0
            Material.NETHERITE_BOOTS -> 3.0
            else -> 0.0
        }

        var armorToughness = when (itemStack.type) {
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS -> 2.0
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS -> 3.0
            else -> 0.0
        }

        var blockBreakSpeed = 1.0
        var blockInteractionRange = 0.0
        var entityInteractionRange = 0.0
        var additionalHealth = 0.0
        var scale = 0.0

        // Добавляем по атрибуту за каждый ролл
        val addedAttributes = mutableListOf<String>()
        do {
            val attribute = attributes.random()
            addedAttributes.add(attribute.toString().replace("GENERIC_", "").replace("PLAYER_", "").replace("_", " ").lowercase())
            when (attribute) {
                Attribute.ATTACK_SPEED -> attackSpeed += 0.3
                Attribute.ATTACK_DAMAGE -> attackDamage += 1.5
                Attribute.ATTACK_SPEED -> blockBreakSpeed += 0.4
                Attribute.BLOCK_INTERACTION_RANGE -> blockInteractionRange += 0.5
                Attribute.MAX_HEALTH -> additionalHealth += 2.0
                Attribute.ARMOR -> armorDefence += 1.0
                Attribute.ARMOR_TOUGHNESS -> armorToughness += 1.0
                Attribute.SCALE -> {
                    scale += 0.1
                    blockInteractionRange += 0.5
                    entityInteractionRange += 0.5
                }
                else -> {}
            }
        }
        while (rolls-- != 0)

        // Фиксим скорость атаки и урон, если это оружие (или инструмент)
        if (slot == EquipmentSlotGroup.MAINHAND) {

            if (attackSpeed > -4 && attackSpeed != 0.0) {
                meta?.addAttributeModifier(Attribute.ATTACK_SPEED, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), attackSpeed, AttributeModifier.Operation.ADD_NUMBER, slot))
            }

            meta?.addAttributeModifier(Attribute.ATTACK_DAMAGE, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), attackDamage, AttributeModifier.Operation.ADD_NUMBER, slot))
        }

        if (blockInteractionRange > 0.0)
            meta?.addAttributeModifier(Attribute.BLOCK_INTERACTION_RANGE, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), blockInteractionRange, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (entityInteractionRange > 0.0)
            meta?.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), blockInteractionRange, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (blockBreakSpeed > 1.0)
            meta?.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), blockBreakSpeed, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (additionalHealth > 0.0)
            meta?.addAttributeModifier(Attribute.MAX_HEALTH, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), additionalHealth, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (armorDefence > 0.0)
            meta?.addAttributeModifier(Attribute.ARMOR, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), armorDefence, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (armorToughness > 0.0)
            meta?.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), armorToughness, AttributeModifier.Operation.ADD_NUMBER, slot))

        if (scale > 0.0)
            meta?.addAttributeModifier(Attribute.SCALE, AttributeModifier(NamespacedKey(plugin, UUID.randomUUID().toString().lowercase()), scale, AttributeModifier.Operation.ADD_NUMBER, slot))

        meta?.persistentDataContainer?.set(attributeKey, PersistentDataType.STRING, addedAttributes.toString())

        meta?.persistentDataContainer?.set(rarityKey, PersistentDataType.STRING, when (rarity) {
            1 -> UniqueItemRarity.COMMON
            2 -> UniqueItemRarity.UNCOMMON
            3 -> UniqueItemRarity.RARE
            4 -> UniqueItemRarity.EPIC
            5 -> UniqueItemRarity.LEGENDARY
            6 -> UniqueItemRarity.MYTHICAL
            else -> UniqueItemRarity.DIVINE
        }.toString())

        // Trim
        (meta as? ArmorMeta)?.let { armorMeta ->
            randomTrimPattern(villager)?.let { pattern ->
                armorMeta.trim = ArmorTrim(randomTrimMaterial(), pattern)
            }
        }

        // Обновляем мету... Нет, правда, почему я должен всё комментировать?..
        itemStack.itemMeta = meta

        return itemStack
    }

    private fun randomTrimMaterial() : TrimMaterial {
        return listOf(TrimMaterial.GOLD, TrimMaterial.IRON, TrimMaterial.COPPER, TrimMaterial.QUARTZ, TrimMaterial.AMETHYST, TrimMaterial.DIAMOND, TrimMaterial.NETHERITE, TrimMaterial.REDSTONE, TrimMaterial.EMERALD, TrimMaterial.LAPIS).random()
    }

    private val trims: Map<Material, TrimPattern>
        get() = mutableMapOf<Material, TrimPattern>().apply {
            put(Material.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.RAISER)
            put(Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.COAST)
            put(Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.RIB)
            put(Material.VEX_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.VEX)
            put(Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.EYE)
            put(Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.BOLT)
            put(Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.DUNE)
            put(Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.FLOW)
            put(Material.HOST_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.HOST)
            put(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SENTRY)
            put(Material.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SHAPER)
            put(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SILENCE)
            put(Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WILD)
            put(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WARD)
            put(Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.TIDE)
            put(Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WAYFINDER)
            put(Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SNOUT)
        }

    private fun randomTrimPattern(villager: Villager) : TrimPattern? {
        return if (plugin.configManager.professions.getBoolean("villager-item-producing.forced-armor-trims")) {
            trims.values.random()
        } else
            villager.subInventory.filterNotNull().filter { it.type.toString().contains("TRIM_SMITHING_TEMPLATE") }.randomOrNull()?.let { trim ->
                trims[trim.type]
            }
    }

    companion object {

        private val plugin = ignisInstance

        private val attributeKey = NamespacedKey(plugin, "attribute")
        private val rarityKey    = NamespacedKey(plugin, "rarity")

        fun ItemStack.isUniqueItem(): Boolean {
            return this.itemMeta?.persistentDataContainer?.has(rarityKey) == true
        }

        fun ItemStack.getUniqueItemRarity(): UniqueItemRarity {
            this.itemMeta?.persistentDataContainer?.get(rarityKey, PersistentDataType.STRING)?.let {
                return UniqueItemRarity.valueOf(it)
            }
            return UniqueItemRarity.NONE
        }

        fun ItemStack.getUniqueItemAttributes(): String {
            return this.itemMeta?.persistentDataContainer?.get(attributeKey, PersistentDataType.STRING)!!
        }


    }

    // TODO: В конфиг.умл эту хуйню.
    enum class UniqueItemRarity(val color: String, val extraPrice: Int) {
        NONE("#ffffff", 0),
        COMMON("#a9a9a9", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.COMMON")),
        UNCOMMON("#00ff00", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.UNCOMMON")),
        RARE("#fae100", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.RARE")),
        EPIC("#00ffff", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.EPIC")),
        LEGENDARY("#fa8100", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.LEGENDARY")),
        MYTHICAL("#ff0000", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.MYTHICAL")),
        DIVINE("#ff50b4", plugin.configManager.professions.getInt("villager-item-producing.extra-rarity-price.DIVINE"))
    }

}