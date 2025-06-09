package me.voxelsquid.anima.humanoid.dialogue

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager.Companion
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import me.voxelsquid.psyche.HumanoidController.Companion.plugin
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getVoicePitch
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getVoiceSound
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

class DialogueManager {

    init {
        dialogueManager = this
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            dialogues.values.forEach(DialogueWindow::relocate)
        }, 0L, 1L)
    }

    fun startDialogue(pair: Pair<Player, Villager>, text: String, follow: Boolean = true, size: Float = 0.35F, interrupt: Boolean = false, onFinish: () -> Unit = {}) {

        val (player, villager) = pair
        val formattedText = dialogueBoxTextBaseColor + text.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
            "$dialogueBoxTextImportantColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
        }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
            "$dialogueBoxTextInterestingColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
        }.replace("\\\"", "\"")

        when (player.dialogueFormat) {

            DialogueFormat.IMMERSIVE -> {

                if (!interrupt && dialogues.containsKey(pair)) {
                    return
                }

                DialogueWindow(plugin, player, villager, size, formattedText.split(" "), follow, interrupt, onFinish).schedule()
            }

            DialogueFormat.CHAT -> {
                this.sendDialogueInChat(player, villager, formattedText)
            }

            DialogueFormat.BOTH -> {

                if (!interrupt && dialogues.containsKey(pair)) {
                    return
                }

                DialogueWindow(plugin, player, villager, size, formattedText.split(" "), follow, interrupt, onFinish).schedule()
                this.sendDialogueInChat(player, villager, formattedText)
            }

        }
    }

    private val cooldownPlayers = mutableListOf<Player>()
    private fun sendDialogueInChat(player: Player, entity: Villager, message: String) {

        // primitive yet clever cooldown system
        if (cooldownPlayers.contains(player)) {
            return
        } else {
            cooldownPlayers.add(player)
            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                cooldownPlayers.remove(player)
            }, 20)
        }

        val formattedMessage = ignisInstance.configManager.language.getString("villager-message-chat-format")!!.replace("{villagerName}", entity.customName ?: "").replace("{message}", message)
        player.sendMessage(formattedMessage)

        if (player.dialogueFormat != DialogueFormat.BOTH)
            player.playSound(entity.location, entity.getVoiceSound(), 1F, entity.getVoicePitch())
    }

    companion object {

        private lateinit var dialogueManager: DialogueManager

        private val dialogueFormatKey = NamespacedKey(ignisInstance, "DialogueFormat")
        private val dialogueBoxSize = ConfigurationAccessor("text-formatting.dialogue-box.size", 0.28F).get()
        private val dialogueBoxTextBaseColor = ConfigurationAccessor("text-formatting.dialogue-box.text.base-color", "&f", mutableListOf("Standard color of common words in dialogue boxes.")).get()
        private val dialogueBoxTextImportantColor = ConfigurationAccessor("text-formatting.dialogue-box.text.important-color", "&5", mutableListOf("Color of important words in dialogue boxes that the AI will try to pay attention to.")).get()
        private val dialogueBoxTextInterestingColor = ConfigurationAccessor("text-formatting.dialogue-box.text.interesting-color", "&6", mutableListOf("Color of interesting words in dialogue boxes that may be interesting to the player.")).get()
        private val dialogueBackgroundAlpha = ConfigurationAccessor("text-formatting.dialogue-box.background.alpha", 185, mutableListOf("0-255")).get()
        private val dialogueBackgroundRed = ConfigurationAccessor("text-formatting.dialogue-box.background.red", 0, mutableListOf("0-255")).get()
        private val dialogueBackgroundGreen = ConfigurationAccessor("text-formatting.dialogue-box.background.green", 0, mutableListOf("0-255")).get()
        private val dialogueBackgroundBlue = ConfigurationAccessor("text-formatting.dialogue-box.background.blue", 0, mutableListOf("0-255")).get()

        val dialogues: MutableMap<Pair<Player, Villager>, DialogueWindow> = mutableMapOf()

        fun Villager.talk(player: Player, text: String?, displaySize: Float = dialogueBoxSize, followDuringDialogue: Boolean = true, interruptPreviousDialogue: Boolean = false, onFinish: () -> Unit = {}) {
            text?.let {
                if (ignisInstance.controller.geyserProvider?.checkGeyserPlayer(player) == true) {
                    dialogueManager.sendDialogueInChat(player, this, text)
                    return
                }
                dialogueManager.startDialogue(player to this, it, size = displaySize, follow = followDuringDialogue, interrupt = interruptPreviousDialogue, onFinish = onFinish)
            }
        }

        enum class DialogueFormat {
            IMMERSIVE, CHAT, BOTH
        }

        val Player.dialogueFormat: DialogueFormat
            get() {
                this.persistentDataContainer.get(dialogueFormatKey, PersistentDataType.STRING)?.let { type ->
                    return DialogueFormat.valueOf(type)
                }
                return ignisInstance.controller.format.also { type ->
                    this.persistentDataContainer.set(dialogueFormatKey, PersistentDataType.STRING, type.toString())
                }
            }

    }

    class DialogueWindow(
        private val plugin: JavaPlugin,
        private val player: Player,
        val entity: Villager,
        private val size: Float,
        private val words: List<String>,
        private val follow: Boolean,
        cancelPrevious: Boolean,
        private val onFinish: () -> Unit = {}
    ) {

        private val display: TextDisplay
        private val displayBackgroundColor = Color.fromARGB(dialogueBackgroundAlpha, dialogueBackgroundRed, dialogueBackgroundGreen, dialogueBackgroundBlue)

        private val voice: Sound = entity.getVoiceSound()
        private val pitch: Float = entity.getVoicePitch()

        private val height = if (!entity.isAdult) 0.75 else 1.25
        private val maxDistance = 5.5

        private val pauseDurationBetweenSentences = 3000L
        private val pauseDurationBetweenWords = 175L

        // If player is SNEAKING during dialogue, it will speed up!
        private val fastPauseDurationBetweenSentences = 1250L
        private val fastPauseDurationBetweenWords = 100L

        private var isCancelled = false
        private var isDestroyed = false

        init {
            if (cancelPrevious) dialogues[player to entity]?.let {
                it.display.remove()
                it.isCancelled = true
            }
            display = entity.world.spawnEntity(entity.location, EntityType.TEXT_DISPLAY) as TextDisplay
            dialogues[player to entity] = this
        }

        fun schedule() {

            display.billboard = Display.Billboard.CENTER
            display.isSeeThrough = false
            display.isVisibleByDefault = false
            player.showEntity(plugin, display)
            display.transformation =
                Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())

            display.backgroundColor = displayBackgroundColor

            val task = object : BukkitRunnable() {
                override fun run() {

                    var wordAmount = 0

                    for (word in words) {

                        if (!plugin.isEnabled || word.isEmpty() || isCancelled || isDestroyed)
                            break

                        val sentence = word.last() == '.' || word.last() == '!' || word.last() == '?' || word.last() == ','
                        val lastWord = words.indexOf(word) == words.lastIndex
                        val clear    = ++wordAmount > 10 && sentence && !lastWord

                        plugin.server.scheduler.runTask(plugin) { _ ->

                            display.text += "$word "
                            player.playSound(entity.location, voice, 1F, pitch)

                            if (follow) instance.entityProvider.asHumanoid(entity as LivingEntity).talkingPlayer = player
                        }

                        val pauseDuration = when {
                            player.isSneaking && sentence -> fastPauseDurationBetweenSentences
                            player.isSneaking -> fastPauseDurationBetweenWords
                            sentence || lastWord -> if (word.last() != ',' || clear) pauseDurationBetweenSentences else pauseDurationBetweenWords * 3
                            else -> pauseDurationBetweenWords
                        }

                        Thread.sleep(pauseDuration)

                        if (clear) {
                            display.text = dialogueBoxTextBaseColor
                            wordAmount = 0
                        }
                    }

                    if (plugin.isEnabled && !isDestroyed) {
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            destroy()
                            onFinish.invoke()
                        }
                    }
                }
            }

            task.runTaskAsynchronously(plugin)
        }

        fun relocate() {

            if (checkDistance()) {
                this.destroy()
                return
            }

            display.teleport(this.calculatePosition())
        }

        fun destroy() {
            display.remove()
            instance.entityProvider.asHumanoid(entity as LivingEntity).talkingPlayer = null
            dialogues.remove(player to entity, this@DialogueWindow)
            isDestroyed = true
        }

        private fun checkDistance(): Boolean = player.location.distance(display.location) > maxDistance

        private fun calculatePosition(): Location {
            return player.eyeLocation.add(entity.location.add(0.0, if (entity.pose != Pose.SLEEPING) height else height - 0.4, 0.0)).multiply(0.5)
        }

    }


}