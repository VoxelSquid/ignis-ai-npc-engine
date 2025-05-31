package me.voxelsquid.anima.event

import me.voxelsquid.anima.quest.base.Quest
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class PlayerAcceptQuestEvent(val player: Player, val villager: Villager, val questData: Quest.QuestData) : Event() {

    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    companion object {

        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }

    }

}