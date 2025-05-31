package me.voxelsquid.anima.humanoid.dialogue

import me.voxelsquid.anima.Anima.Companion.ignisInstance
import org.bukkit.entity.*

class DialogueManager {

    fun startDialogue(pair: Pair<Player, Villager>, text: String) {
        val (player, villager) = pair
        this.sendDialogueInChat(player, villager, text)
    }

    private val cooldownPlayers = mutableListOf<Player>()
    private fun sendDialogueInChat(player: Player, entity: Villager, message: String) {

        if (cooldownPlayers.contains(player)) {
            return
        } else {
            cooldownPlayers.add(player)
            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                cooldownPlayers.remove(player)
            }, 20)
        }

        val formattedMessage = plugin.configManager.language.getString("villager-message-chat-format")!!.replace("{villagerName}", entity.customName ?: "").replace("{message}", message)
        player.sendMessage(formattedMessage)
    }

    companion object {

        private val plugin = ignisInstance
        fun Villager.talk(player: Player, text: String?) {
            text?.let { ignisInstance.humanoidManager.dialogueManager.startDialogue(
                player to this,
                it
            ) }
        }

    }

}