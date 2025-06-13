package me.voxelsquid.anima.humanoid

import me.voxelsquid.psyche.race.RaceManager.Companion.race
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.event.MerchantTradeEvent
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addItemToQuillInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.takeItemFromQuillInventory
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.getMaterialPrice
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe

class HumanoidTradeHandler : Listener {

    @EventHandler
    private fun handleMerchantInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.type == InventoryType.MERCHANT) {
            val player   = (event.whoClicked as Player)
            val villager = playerTradingInventories[player] ?: return
            val slot     = if (event.rawSlot != 2) return else 2

            val rewardItem  = event.inventory.getItem(slot) ?: return
            val firstTrade  = event.inventory.getItem(0) ?: return

            if (rewardItem.type != Material.AIR) {
                val recipe = villager.recipes.find { it.result.isSimilar(rewardItem) && it.ingredients[0].isSimilar(firstTrade) } ?: throw NullPointerException("Null recipe on successful trade.")

                // Отправляем на следующем тике, чтобы прошёл трейд.
                Bukkit.getServer().scheduler.runTask(plugin) { _ ->
                    Bukkit.getServer().pluginManager.callEvent(MerchantTradeEvent(villager, player, recipe))
                }

            }
        }
    }

    @EventHandler
    private fun handleTrade(event: MerchantTradeEvent) {
        (event.merchant as? Villager)?.let { villager: Villager ->
            event.recipe.let { recipe ->
                villager.quests.find{it.getRequiredItem().isSimilar(recipe.ingredients[0])}?.let {
                    if (it.questType == Quest.Type.HUNTING) return
                }
                villager.addItemToQuillInventory(recipe.ingredients[0])
                if (recipe.ingredients.size > 1) villager.addItemToQuillInventory(recipe.ingredients[1])
                villager.takeItemFromQuillInventory(recipe.result, recipe.result.amount)
            }
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object {

        private val plugin = ignisInstance
        private data class TradingSlot(var currency: Material, var amount: Int) {

            fun calculateAmount(price: Int) : Int {
                return price / currency.getMaterialPrice()
            }

            fun totalPrice() : Int {
                return currency.getMaterialPrice() * amount
            }

            fun toItemStack() : ItemStack = ItemStack(currency, amount)

        }

        private val Villager.producedItems: List<ItemStack>
            get() {
                val itemsToProduce = plugin.configManager.professions.getStringList("villager-item-producing.profession.${this.profession}.item-produce")
                return subInventory.filterNotNull().filter { itemStack -> itemsToProduce.contains(itemStack.type.toString()) }.toList()
            }

        val playerTradingInventories = mutableMapOf<Player, Villager>()
        fun Villager.openTradeMenu(player: Player, open: Boolean = true) : Boolean {

            // Each race has own currency.
            val race = race
            val currency = race.normalCurrency
            val specialCurrency = race.specialCurrency

            // Clean basic merchant recipes.
            recipes = mutableListOf<MerchantRecipe>()
            val playerQuests = player.quests

            // Update quests and make them first in the trade GUI.
            val questTrades = mutableListOf<MerchantRecipe>()
            if (quests.isNotEmpty()) {
                quests.forEach { quest ->

                    // Only accepted quests can be finished.
                    if (playerQuests.find { it.questID == quest.questID } != null) {
                        val recipe = MerchantRecipe(Quest.calculateBounty(this, quest.score), 1)
                        recipe.addIngredient(quest.getRequiredItem())
                        questTrades += recipe
                    }

                }
            }

            val tradeProfessionItemsOnly = plugin.configManager.professions.getBoolean("villager-item-producing.trade-profession-items-only")
            val itemsToTrade = if (tradeProfessionItemsOnly) producedItems else subInventory.filterNotNull()

            val settlement = settlement
            val multiplier = if (settlement != null ) player.getPlayerReputationStatus(settlement).priceMultiplier.get().toFloat() else 1.0F

            // Going through every produced item.
            itemsToTrade.forEach { item ->

                // Trade a currency? Really?
                if (item.type == race.normalCurrency.get())
                    return@forEach

                // We skip adding identical trades
                if (recipes.find { recipe -> recipe.result.isSimilar(item) } != null) {
                    return@forEach
                }

                // We're trading just one item.
                val total = item.amount
                item.amount = 1

                val trade = TradingSlot(Material.AIR, 0) to TradingSlot(Material.AIR, 0)
                val price = (item.calculatePrice() * multiplier).toInt()

                // Use the special currency if needed.
                val useSpecialCurrency = price / (currency.get() ?: throw NullPointerException("Can't init currency!")).getMaterialPrice() > (currency.get() ?: throw NullPointerException("Can't init currency!")).maxStackSize * 2

                // First things first.
                trade.first.let { firstSlot ->

                    firstSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                    val useSecondSlot = firstSlot.calculateAmount(price) > firstSlot.currency.maxStackSize

                    if (!useSecondSlot) {
                        firstSlot.amount = firstSlot.calculateAmount(price)
                    } else {
                        firstSlot.amount = firstSlot.currency.maxStackSize
                        trade.second.let { secondSlot ->
                            secondSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                            secondSlot.amount   = secondSlot.calculateAmount(price - firstSlot.totalPrice())
                        }
                    }

                    if (price - firstSlot.totalPrice() > 0 && !useSecondSlot && useSpecialCurrency) {
                        trade.second.currency = currency.get() ?: throw NullPointerException("Can't init currency!")
                        trade.second.amount   = trade.second.calculateAmount(price - firstSlot.totalPrice())
                    }
                }

                // If villager tries to sell something really cheap, skip it.
                if (trade.first.amount == 0)
                    return@forEach

                val recipe = MerchantRecipe(item, total)
                recipe.addIngredient(trade.first.toItemStack())

                if (trade.second.amount > 0)
                    recipe.addIngredient(trade.second.toItemStack())

                this.recipes = recipes + recipe
            }

            // Sort by type, then by price.
            val sorted = recipes.toMutableList().sortedWith(compareBy({ it.result.type }, { it.result.calculatePrice() }))

            if (recipes.isEmpty() && questTrades.isEmpty()) {
                this.shakeHead()
                return false
            }

            if (open) {
                // Quest trades always first.
                recipes = questTrades + sorted

                player.openMerchant(this, false)
                playerTradingInventories[player] = this
            }

            return true
        }

    }

}