package me.voxelsquid.ignis.gameplay.humanoid.dialogue

import me.voxelsquid.ignis.Ignis.Companion.pluginInstance
import me.voxelsquid.ignis.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.ignis.Ignis.Companion.sendVerbose
import me.voxelsquid.ignis.config.ConfigurationAccessor
import me.voxelsquid.ignis.gameplay.event.HumanoidGiftReactionEvent
import me.voxelsquid.ignis.gameplay.event.HumanoidResponseEvent
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager.HumanoidEntityExtension.addItemToQuillInventory
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager.HumanoidEntityExtension.getVoicePitch
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager.HumanoidEntityExtension.getVoiceSound
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidTradeHandler.Companion.openTradeMenu
import me.voxelsquid.ignis.settlement.ReputationManager.Companion.addReputation
import net.minecraft.world.InteractionHand
import org.bukkit.Sound
import org.bukkit.craftbukkit.v1_21_R4.entity.CraftPlayer
import org.bukkit.craftbukkit.v1_21_R4.entity.CraftVillager
import org.bukkit.craftbukkit.v1_21_R4.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.scheduler.BukkitTask

class DialogueSession(val player: Player, val entity: Villager) : Listener {

    private val plugin = pluginInstance

    var readyToSend = true
    var cancelled = false
        set(value) {
            if (value) {
                player.sendFormattedMessage(conversationEndedMessage.replace("{npcName}", entity.customName ?: "NPC"))
                HandlerList.unregisterAll(this)
                activeDialogueSessions.remove(this)
                (entity as CraftVillager).handle.tradingPlayer = null
            }
            field = value
        }

    var giftAwaiting = false
        set(value) {
            if (value) {
                player.sendFormattedMessage(waitingForGiftMessage.replace("{npcName}", entity.customName ?: "NPC"))
            }
            field = value
        }

    init {
        player.sendFormattedMessage(conversationStartedMessage.replace("{npcName}", entity.customName ?: "NPC"))
        plugin.server.pluginManager.registerEvents(this, plugin)
        activeDialogueSessions.add(this)
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            this.keepAlive(task)
        }, 0L, 20L)
    }

    var lastMessageTime = System.currentTimeMillis()
    val dialogueHistory = mutableListOf<String>()

    private fun keepAlive(task: BukkitTask) {

        if (cancelled) {
            task.cancel()
            return
        }

        val timeout        = (System.currentTimeMillis() - lastMessageTime) / 1000 > 120
        val tooFar         = if (player.world != entity.world) true else player.location.distance(entity.location) > 8
        val differentWorld = player.world != entity.world
        val someoneIsDead  = player.isDead || entity.isDead
        if (timeout || tooFar || differentWorld || someoneIsDead) {
            this.cancelled = true
        } else {
            (entity as CraftVillager).handle.tradingPlayer = (player as CraftPlayer).handle
        }

    }

    @EventHandler
    private fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (giftAwaiting && event.player == player && !cancelled) {
            if (readyToSend) {
                this.cooldown()
                plugin.geminiProvider.generateGiftReaction(player, entity, event.itemDrop.itemStack.clone(), dialogueHistory)
                (entity as CraftVillager).handle.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(event.itemDrop.itemStack))
                giftAwaiting = false
                readyToSend = false
                lastMessageTime = System.currentTimeMillis()
                event.itemDrop.remove()
                player.playSound(entity.eyeLocation, entity.getVoiceSound(), 1F, entity.getVoicePitch())
            } else player.sendFormattedMessage(cooldownMessage)
        }
    }

    @EventHandler
    private fun onPlayerChat(event: AsyncPlayerChatEvent) {
        if (event.player == player && !cancelled) {
            event.isCancelled = true
            if (readyToSend) {
                this.handleMessage(event.message)
                dialogueHistory.add("${player.name}: \"${event.message}\" ->")
                lastMessageTime = System.currentTimeMillis()
                player.sendFormattedMessage(playerToNPCMessage.replace("{playerName}", player.name).replace("{message}", event.message))
            } else player.sendFormattedMessage(cooldownMessage)
        }
    }

    private enum class Impression(val score: Int) {
        DISASTROUS(-100), TERRIBLE(-25), BAD(-10), POOR(-5), MEDIOCRE(-1), NEUTRAL(0), GOOD(5), GREAT(10), EXCELLENT(25), AMAZING(50), PERFECT(100);
    }

    private enum class Directive {
        NONE, OPEN_TRADE_MENU, INTERRUPT_CONVERSATION;
    }

    @EventHandler
    private fun onHumanoidResponse(event: HumanoidResponseEvent) {
        if (!cancelled) {
            event.response.npcResponse.forEach { dialogueHistory.add("${entity.customName}: \"$it\" ->") }

            // NPC memory modification.
            entity.getMemory().let { memory ->
                memory.shortMemory.add(event.response.memoryNode)
                memory.opinions[player.uniqueId] = event.response.updatedOpinionOnPlayer
                memory.save(entity)
            }

            this.verbose(event)
            val impression = Impression.valueOf(event.response.impression)
            val directive  = Directive.valueOf(event.response.directive)

            // Sending messages.
            var delay = 0L
            for (message in event.response.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message))
                    player.playSound(player.eyeLocation, Sound.UI_HUD_BUBBLE_POP, 1F, 1.25F)
                    // Handling directive only on last message of the response.
                    if (event.response.npcResponse.last() == message) {
                        // Modifying reputation after talking. We should add check for it.
                        entity.settlement?.addReputation(player, impression.score)
                        readyToSend = true
                        when (directive) {
                            Directive.OPEN_TRADE_MENU -> entity.openTradeMenu(player)
                            Directive.INTERRUPT_CONVERSATION -> this.cancelled = true
                            Directive.NONE -> { /* I have nothing to do. */}
                        }
                    }
                }, delay)
                delay += 60L
            }

        }
    }

    @EventHandler
    private fun onGiftReaction(event: HumanoidGiftReactionEvent) {
        if (!cancelled) {
            event.reaction.npcResponse.forEach { dialogueHistory.add("${entity.customName}: \"$it\" ->") }

            // NPC memory modification.
            entity.getMemory().let { memory ->
                memory.shortMemory.add(event.reaction.memoryNode)
                memory.opinions[player.uniqueId] = event.reaction.updatedOpinionOnPlayer
                memory.save(entity)
            }

            this.verbose(event)
            val impression = Impression.valueOf(event.reaction.impression)

            // Sending messages.
            var delay = 0L
            for (message in event.reaction.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message))
                    player.playSound(player.eyeLocation, Sound.UI_HUD_BUBBLE_POP, 1F, 1.25F)
                    // Handling directive only on last message of the response.
                    if (event.reaction.npcResponse.last() == message) {
                        if (!event.reaction.keepTheGift) {
                            entity.world.dropItem(entity.location, event.gift)
                        } else entity.addItemToQuillInventory(event.gift)
                        readyToSend = true
                        // Modifying reputation after talking. We should add check for it.
                        entity.settlement?.addReputation(player, impression.score)
                    }
                }, delay)
                delay += 60L
            }

        }
    }

    @EventHandler
    private fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity == entity) {
            cancelled = true
        }
    }

    private fun handleMessage(message: String) {
        this.cooldown()
        plugin.geminiProvider.generateChatReply(player, entity, message, dialogueHistory)
    }

    private fun cooldown() {
        readyToSend = false
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            readyToSend = true
        }, 200L)
    }

    // Simple debug stuff.
    private fun verbose(event: HumanoidResponseEvent) {
        player.sendVerbose("§7==-- [Debug] Response from §6${event.entity.customName}§7! [Debug] --==")
        player.sendVerbose("§7§l╓ §e[Messages (${event.response.npcResponse.size})] §f${event.response.npcResponse}")
        player.sendVerbose("§7§l╟ §e[Memory Input] §f${event.response.memoryNode}")
        player.sendVerbose("§7§l╟ §e[Updated Opinion] §f${event.response.updatedOpinionOnPlayer}")
        player.sendVerbose("§7§l╟ §e[Impression] §f${event.response.impression}")
        player.sendVerbose("§7§l╙ §e[Directive] §f${event.response.directive}")
    }

    private fun verbose(event: HumanoidGiftReactionEvent) {
        player.sendVerbose("§7==-- [Debug] Response from §6${event.entity.customName}§7! [Debug] --==")
        player.sendVerbose("§7§l╓ §e[Messages (${event.reaction.npcResponse.size})] §f${event.reaction.npcResponse}")
        player.sendVerbose("§7§l╟ §e[Memory Input] §f${event.reaction.memoryNode}")
        player.sendVerbose("§7§l╟ §e[Updated Opinion] §f${event.reaction.updatedOpinionOnPlayer}")
        player.sendVerbose("§7§l╟ §e[Impression] §f${event.reaction.impression}")
        player.sendVerbose("§7§l╙ §e[Keep The Gift] §f${event.reaction.keepTheGift}")
    }

    companion object {

        private val npcResponseMessage = ConfigurationAccessor(path = "text-formatting.chat.npc-message", defaultValue = "&7{npcName}&6ᵃⁱ&7: &f{message}", comments = mutableListOf("NPC response during conversations. It is recommended to hint to the player that they are talking to the AI.")).get()
        private val playerToNPCMessage = ConfigurationAccessor(path = "text-formatting.chat.player-to-npc-message", defaultValue = "&7{playerName}&6ᵃⁱ&7: &f{message}", comments = mutableListOf("Message from a player during a dialogue session.")).get()
        private val conversationStartedMessage = pluginInstance.configManager.language.getString("info-messages.npc-conversation.started")!!
        private val conversationEndedMessage   = pluginInstance.configManager.language.getString("info-messages.npc-conversation.ended")!!
        private val cooldownMessage            = pluginInstance.configManager.language.getString("info-messages.npc-conversation.cooldown")!!
        private val waitingForGiftMessage      = pluginInstance.configManager.language.getString("info-messages.npc-conversation.waiting-for-gift")!!

        val activeDialogueSessions = mutableListOf<DialogueSession>()
        fun Player.getActiveDialogueSession() : DialogueSession? {
            return activeDialogueSessions.find { it.player == this }
        }

    }

}