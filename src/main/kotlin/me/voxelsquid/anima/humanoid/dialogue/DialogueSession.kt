package me.voxelsquid.anima.humanoid.dialogue

import com.cryptomorin.xseries.XSound
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.professionLevelName
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidTradeHandler.Companion.openTradeMenu
import me.voxelsquid.anima.humanoid.memory.MemoryManager.Companion.getEmotionalMemory
import me.voxelsquid.anima.settlement.ReputationManager.Companion.addReputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import me.voxelsquid.anima.utility.Daytime
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.psyche.HumanoidController.Companion.bifrost
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.gender
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityType
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.scheduler.BukkitTask

class DialogueSession(val player: Player, val entity: Villager) : Listener {

    var readyToSend = true
    var cancelled = false
        set(value) {
            if (value) {
                player.sendFormattedMessage(conversationEndedMessage.replace("{npcName}", entity.customName ?: "NPC"))
                HandlerList.unregisterAll(this)
                activeDialogueSessions.remove(this)
                instance.entityProvider.asHumanoid(entity as LivingEntity).talkingPlayer = null
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
            instance.entityProvider.asHumanoid(entity as LivingEntity).talkingPlayer = player
        }

    }

    /*

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

     */

    @EventHandler
    private fun onPlayerChat(event: AsyncPlayerChatEvent) {
        if (event.player == player && !cancelled) {
            event.isCancelled = true
            if (readyToSend) {
                plugin.server.scheduler.runTask(plugin) { _ ->
                    this.handleMessage(event.message)
                }
                dialogueHistory.add("${player.name}: \"${event.message}\" ->")
                lastMessageTime = System.currentTimeMillis()
                player.sendFormattedMessage(playerToNPCMessage.replace("{playerName}", player.name).replace("{message}", event.message))
            } else player.sendFormattedMessage(cooldownMessage)
        }
    }

    data class NPCChatResponseData(val npcResponse: List<String>, val memoryNode: String, val impression: String, val updatedOpinionOnPlayer: String, val directive: String)
    fun generateChatReply(player: Player, villager: Villager, playerMessage: String, dialogue: MutableList<String>) {

        val npcName    = villager.customName ?: "unknown"
        val settlement = villager.settlement

        val opinionOnPlayer = villager.getEmotionalMemory().opinions[player.uniqueId] ?: "Unknown. It is their first meeting."
        val shortMemory     = villager.getEmotionalMemory().shortMemory.toString()

        val playerReputation = settlement?.let { player.getPlayerReputationStatus(settlement).toString() } ?: "NEUTRAL"
        val tradeReadiness   = when {
            villager.profession == Villager.Profession.NONE -> "NPC can't trade due to lack of the profession. Help them find any! NPC must point it in the response."
            !villager.openTradeMenu(player, open = false) -> "NPC has a profession, but cannot trade due to poorness: the NPC simply does not have items to trade."
            Daytime.fromWorldTime(villager.world.time) == Daytime.NIGHT -> "NPC must be annoyed by the player's attempt to trade during a time when all normal people are sleeping and dreaming!"
            else -> "NPC is ready to trade with the player. If the player suggests to trade, NPC will respond positively."
        }

        val race = (villager as LivingEntity).race

        val currentBiome   = villager.world.getBiome(villager.location).key.key
        val currentDaytime = Daytime.fromWorldTime(villager.world.time).toString().lowercase()
        val currentWeather = villager.world.let { if (it.isThundering) return@let "thunder" else if (it.isClearWeather) "clear" else "raining" }
        val activeEffects  = villager.activePotionEffects.map { it.type.toString() }.toString()
        val wealth = HumanoidManager.InventoryWealth.getWealthLevel(
            villager.subInventory.contents.filterNotNull().toList().calculatePrice()
        )

        val placeholders = mapOf(
            "playerName"         to player.name,
            "opinionOnPlayer"    to opinionOnPlayer,
            "npcName"            to npcName,
            "npcRace"            to race.name.replace("_", " "),
            "raceLore"           to race.description,
            "npcGender"          to villager.gender.toString(),
            "npcPersonality"     to villager.getPersonalityType().toString(),
            "npcProfession"      to "${villager.profession}",
            "npcProfessionLevel" to villager.professionLevelName,
            "settlementName"     to (settlement?.data?.settlementName ?: "[NO SETTLEMENT]"),
            "playerReputation"   to playerReputation,
            "currentBiome"       to currentBiome,
            "currentTime"        to currentDaytime,
            "currentWeather"     to currentWeather,
            "activeEffects"      to activeEffects,
            "playerMessage"      to playerMessage,
            "dialogueHistory"    to if (dialogue.isEmpty()) "[NO PREVIOUS MESSAGES. IT IS THE START OF THE DIALOGUE. GREET THE PLAYER IF NEEDED.]" else dialogue.toString(),
            "shortMemory"        to shortMemory,
            "tradeReadiness"     to tradeReadiness,
            "wealth"             to wealth.toString()
        )

        val prompt = placeholders.entries.fold(ignisInstance.configManager.prompts.getString("npc-chat")!!) { acc, entry ->
            acc.replace("{${entry.key}}", entry.value)
        }

        bifrost.client.sendRequest(prompt,
            NPCChatResponseData::class,
            onSuccess = { response ->
                this.handleChatResponse(response)
            }, onFailure = { error ->
                player.sendMessage("AI is overloaded, response generation can take a bit of time... (send this in actionbar idiot)")
            }
        )

    }

    private enum class Impression(val score: Int) {
        DISASTROUS(-100), TERRIBLE(-25), BAD(-10), POOR(-5), MEDIOCRE(-1), NEUTRAL(0), GOOD(5), GREAT(10), EXCELLENT(25), AMAZING(50), PERFECT(100);
    }

    private enum class Directive {
        NONE, OPEN_TRADE_MENU, INTERRUPT_CONVERSATION;
    }

    private fun handleChatResponse(responseData: NPCChatResponseData) {
        if (!cancelled) {
            responseData.npcResponse.forEach { dialogueHistory.add("${entity.customName}: \"$it\" ->") }

            // NPC memory modification.
            entity.getEmotionalMemory().let { memory ->
                memory.shortMemory.add(responseData.memoryNode)
                memory.opinions[player.uniqueId] = responseData.updatedOpinionOnPlayer
                memory.save(entity)
            }

            val impression = Impression.valueOf(responseData.impression)
            val directive  = Directive.valueOf(responseData.directive)

            // Sending messages.
            var delay = 0L
            for (message in responseData.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message))
                    player.playSound(player.eyeLocation, XSound.UI_TOAST_IN.get() ?: throw NullPointerException(), 1F, 1.25F)
                    // Handling directive only on last message of the response.
                    if (responseData.npcResponse.last() == message) {
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

    /*
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

            val impression = Impression.valueOf(event.reaction.impression)

            // Sending messages.
            var delay = 0L
            for (message in event.reaction.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message))
                    player.playSound(player.eyeLocation, XSound.UI_HUD_BUBBLE_POP.get() ?: throw NullPointerException(), 1F, 1.25F)
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

     */

    @EventHandler
    private fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity == entity) {
            cancelled = true
        }
    }

    private fun handleMessage(message: String) {
        this.cooldown()
        this.generateChatReply(player, entity, message, dialogueHistory)
    }

    private fun cooldown() {
        readyToSend = false
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            readyToSend = true
        }, 200L)
    }

    companion object {

        private val plugin = ignisInstance
        private val npcResponseMessage = ConfigurationAccessor(path = "text-formatting.chat.npc-message", defaultValue = "&7{npcName}&6ᵃⁱ&7: &f{message}", comments = mutableListOf("NPC response during conversations. It is recommended to hint to the player that they are talking to the AI.")).get()
        private val playerToNPCMessage = ConfigurationAccessor(path = "text-formatting.chat.player-to-npc-message", defaultValue = "&7{playerName}&6ᵃⁱ&7: &f{message}", comments = mutableListOf("Message from a player during a dialogue session.")).get()
        private val conversationStartedMessage = plugin.configManager.language.getString("info-messages.npc-conversation.started")!!
        private val conversationEndedMessage   = plugin.configManager.language.getString("info-messages.npc-conversation.ended")!!
        private val cooldownMessage            = plugin.configManager.language.getString("info-messages.npc-conversation.cooldown")!!
        private val waitingForGiftMessage      = plugin.configManager.language.getString("info-messages.npc-conversation.waiting-for-gift")!!

        val activeDialogueSessions = mutableListOf<DialogueSession>()
        fun Player.getActiveDialogueSession() : DialogueSession? {
            return activeDialogueSessions.find { it.player == this }
        }

    }

}