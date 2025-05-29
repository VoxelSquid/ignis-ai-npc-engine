package me.voxelsquid.ignis.humanoid.race

import com.github.retrooper.packetevents.protocol.player.TextureProperty
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

class HumanoidRaceManager {

    data class PitchedSound(val sound: Sound, val min: Double, val max: Double)

    data class Race(
        val name: String,
        val targetEntityType: EntityType,
        val targetVillagerType: Villager.Type,
        val maleVoices: List<PitchedSound>,
        val femaleVoices: List<PitchedSound>,
        val maleHurtSound: PitchedSound,
        val maleDeathSound: PitchedSound,
        val femaleHurtSound: PitchedSound,
        val femaleDeathSound: PitchedSound,
        val spawnItems: List<SpawnItemStack>,
        val attributes: Map<Attribute, Double>,
        val maleSkins: List<TextureProperty>,
        val femaleSkins: List<TextureProperty>,
        val description: String = "",
        val normalCurrency: Material,
        val specialCurrency: Material
    ) {

        // A predicate that simplifies the verification of an entity that can be racially labeled.
        val matching: (LivingEntity) -> Boolean = { entity ->
            entity is Villager && entity.villagerType == targetVillagerType || entity !is Villager && entity.type == targetEntityType
        }

        companion object {

            private val voices = listOf(
                Sound.ENTITY_WANDERING_TRADER_YES,
                Sound.ENTITY_WANDERING_TRADER_NO,
                Sound.ENTITY_VILLAGER_YES,
                Sound.ENTITY_VILLAGER_NO,
                Sound.ENTITY_VINDICATOR_AMBIENT,
                Sound.ENTITY_VINDICATOR_CELEBRATE,
                Sound.ENTITY_VILLAGER_TRADE,
                Sound.ENTITY_PILLAGER_AMBIENT,
                Sound.ENTITY_WITCH_AMBIENT
            ).map { PitchedSound(it, 0.9, 1.05) }

            // Default villager race will be used if humanoid-villagers in config.yml is false.
            val VILLAGER_RACE = Race(
                "villager",
                EntityType.VILLAGER,
                Villager.Type.PLAINS,
                voices, voices,
                PitchedSound(Sound.ENTITY_VILLAGER_HURT, 0.95, 1.05),
                PitchedSound(Sound.ENTITY_VILLAGER_DEATH, 0.95, 1.05),
                PitchedSound(Sound.ENTITY_VILLAGER_HURT, 0.95, 1.05),
                PitchedSound(Sound.ENTITY_VILLAGER_DEATH, 0.95, 1.05),
                listOf(
                    SpawnItemStack(Material.EMERALD, 32, 64),
                    SpawnItemStack(Material.IRON_INGOT, 32, 64),
                    SpawnItemStack(Material.LEATHER, 32, 64),
                    SpawnItemStack(Material.DIAMOND, 2, 4),
                    SpawnItemStack(Material.BREAD, 32, 64),
                    SpawnItemStack(Material.STICK, 16, 32),
                    SpawnItemStack(Material.APPLE, 32, 64)
                ), mapOf(), listOf(), listOf(), "", Material.EMERALD, Material.EMERALD_BLOCK
            )
        }

    }

    data class SpawnItemStack(private val material: Material, private val min: Int, private val max: Int) {
        fun build(): ItemStack = ItemStack(material, Random.nextInt(min, max))
    }

    companion object {

        val racesRegistry = hashMapOf<String, Race>()

        val Villager.race: Race
            get() {
                return racesRegistry.values.find { race ->
                    race.matching(this)
                } ?: Race.VILLAGER_RACE
            }
    }

}