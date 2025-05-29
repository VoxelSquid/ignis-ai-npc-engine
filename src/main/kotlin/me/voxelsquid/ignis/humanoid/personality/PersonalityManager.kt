package me.voxelsquid.ignis.humanoid.personality

import me.voxelsquid.ignis.Ignis.Companion.ignisInstance
import me.voxelsquid.ignis.humanoid.race.HumanoidGender
import me.voxelsquid.ignis.humanoid.race.HumanoidRaceManager.Companion.race
import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import java.util.*

class PersonalityManager {

    val targetEntityTypes = mutableListOf(EntityType.VILLAGER)

    init {
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            this.tick()
        }, 0, plugin.controller.personalityGenerationPeriod)
    }

    // Генерация персоны будет происходить для каждой энтити, которая указана в targetEntityTypes и не имеет персоны.
    // Это позволит другим плагинам подключаться сюда и указывать других энтитей, например бродячих торговцев или ведьм.
    private fun tick() {
        plugin.allowedWorlds.forEach { world ->
            world.entities.filterIsInstance<LivingEntity>().find { this.targetEntityTypes.contains(it.type) && it.getPersonalityData() == null }?.let { entity ->
                this.generatePersonality(entity)
            }
        }
    }

    private fun generatePersonality(entity: LivingEntity) {
        val personalityType = entity.getPersonalityType().toString().lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val race    = (entity as? Villager)?.race
        val desc    = race?.description ?: ""
        val setting = plugin.controller.setting
        val style   = plugin.controller.naming
        val gender  = entity.gender
        plugin.bifrost.client.sendRequest(
            prompt = "Your task is to generate NPC personality data in $setting setting (use only $style, name must be race dependent${if (plugin.controller.swearing && (entity.getPersonalityType() == PersonalityType.RUDE || entity.getPersonalityType() == PersonalityType.ANGRY || entity.getPersonalityType() == PersonalityType.DRUNKARD)) "; 25% of words are swearing" else ""}) and put it in JSON with specified keys: " +
                    "‘npcName’ (string; [first and second; take into account race (${race?.name ?: entity.type.toString().lowercase()}) and race description ($desc), personality type (${personalityType}) and gender ($gender)]), " +
                    "‘backstory’ (string), " +
                    "‘sleepInterruptionPhrases’ (array of 5 strings; [phrases that NPC says when a player disrupts their sleep]), " +
                    "‘damagePhrases’ (array of 5 strings; [phrases that NPC says when attacked by a player]), " +
                    "‘joblessPhrases’ (array of 5 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any job]), " +
                    "‘noItemsToTradePhrases’ (array of 5 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any items to trade]), " +
                    "‘noQuestPhrases’ (array of 5 strings; [phrases that NPC says when a player asks NPC about job, but NPC doesn't have any quests for the player]).",
            responseType = PersonalityData::class,
            onSuccess = { personalityData ->
                entity.setPersonalityData(personalityData)
                entity.customName = personalityData.npcName
            },
            onFailure = { error ->
                println("Error during personality data generation! (${error.message})")
            }
        )
    }

    companion object {

        private val plugin             = ignisInstance
        private val personalityTypeKey = NamespacedKey(plugin, "PersonalityType")
        private val personalityDataKey = NamespacedKey(plugin, "PersonalityData")
        private val genderKey          = NamespacedKey(plugin, "Gender")

        // Тип характера. Определение личности одним словом слишком удобно, чтобы от этого отказываться.
        fun LivingEntity.getPersonalityType() = persistentDataContainer.get(personalityTypeKey, PersistentDataType.STRING)?.let {
            PersonalityType.valueOf(it)
        } ?: PersonalityType.entries.random().also { setPersonalityType(it) }

        fun LivingEntity.setPersonalityType(personalityType: PersonalityType) {
            persistentDataContainer.set(personalityTypeKey, PersistentDataType.STRING, personalityType.toString())
        }

        // Персональная дата, превращающая безыдейного моба в "живого" NPC.
        fun LivingEntity.getPersonalityData(): PersonalityData? {
            return persistentDataContainer.get(personalityDataKey, PersistentDataType.STRING)?.let { data ->
                plugin.gson.fromJson(data, PersonalityData::class.java)
            }
        }

        fun LivingEntity.setPersonalityData(personalityData: PersonalityData) {
            persistentDataContainer.set(personalityDataKey, PersistentDataType.STRING, personalityData.toString())
        }

        // Пол жителя. Лениво определяется в момент вызова.
        val LivingEntity.gender: HumanoidGender
            get() = persistentDataContainer.get(genderKey, PersistentDataType.STRING)?.let {
                HumanoidGender.valueOf(it)
            } ?: HumanoidGender.entries.random().also { persistentDataContainer.set(genderKey, PersistentDataType.STRING, it.toString()) }

        // Персональная дата, удобно сериализующаяся в JSON.
        data class PersonalityData(val npcName: String,
                                   val backstory: String,
                                   val sleepInterruptionPhrases: List<String>,
                                   val damagePhrases: List<String>,
                                   val joblessPhrases: List<String>,
                                   val noItemsToTradePhrases: List<String>,
                                   val noQuestPhrases: List<String>) {

            override fun toString(): String {
                return plugin.gson.toJson(this).toString()
            }

        }

    }

}