package me.voxelsquid.anima.event

import me.voxelsquid.anima.quest.base.Quest
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class QuestInvalidationEvent(val player: Player, val questData: Quest.QuestData, val reason: Reason) : Event() {

    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    enum class Reason {
        NPC_DEATH, TIME_EXPIRATION, FINISHED_BY_SOMEONE_ELSE, NOT_ACTUAL
    }

    companion object {

        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }

    }

}