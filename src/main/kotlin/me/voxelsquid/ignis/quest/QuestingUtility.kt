package me.voxelsquid.ignis.quest

import kotlin.random.Random

object QuestingUtility {

    fun getRandomLetter(): String {
        val letters = 'A'..'Z'
        val randomIndex = Random.nextInt(letters.count())
        return letters.elementAt(randomIndex).toString()
    }

}