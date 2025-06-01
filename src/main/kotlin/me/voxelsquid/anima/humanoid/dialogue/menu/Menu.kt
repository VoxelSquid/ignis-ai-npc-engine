package me.voxelsquid.anima.humanoid.dialogue.menu

import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager.Companion.openedMenuList
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.*
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

class Menu(
    val villager: Villager,
    val viewer: Player,
    var lastScrollTime: Long = 0
) {

    // Позиция для отображения текста
    private val pivot: Location = calculatePosition()
    private val textDisplays: MutableMap<TextDisplay, () -> Unit> = mutableMapOf()

    private val height = 1.4
    private val maxDistance = 5.5
    private val size = 0.4F
    private val step = 0.125

    private val defaultColor = Color.fromARGB(150, 0, 0, 0)
    private val selectedColor = Color.fromARGB(150, 200, 200, 0)

    init {
        openedMenuList.add(this)
    }

    /**
     * Вычисляет позицию отображения текста относительно игрока и жителя.
     */
    private fun calculatePosition(): Location {
        return viewer.eyeLocation.add(villager.location.add(0.0, height, 0.0)).multiply(0.5)
    }

    /**
     * Перемещает GUI, если игрок находится в пределах допустимого расстояния.
     */
    fun relocate() {
        if (viewer.location.distance(villager.location.add(0.0, height, 0.0)) > maxDistance) {
            destroy()
            return
        }
        updatePosition()
        instance.entityProvider.asHumanoid(villager as LivingEntity).talkingPlayer = viewer
    }

    private fun updatePosition() {
        val newLocation = calculatePosition()
        textDisplays.keys.forEachIndexed { index, display ->
            display.teleport(newLocation.clone().add(0.0, -index * step, 0.0))
        }
    }

    var index: Int = 0
        set(value) {
            field = cyclicIndex(value)
            updateSelection()
        }

    /**
     * Добавляет текстовую строку и связанную с ней функцию в GUI.
     *
     * @param text Текст для отображения.
     * @param function Функция, вызываемая при выборе этой строки.
     */
    fun addLine(text: String, function: () -> Unit) {
        val display = createButtonDisplay(text)
        textDisplays[display] = function
        updateSelection()
    }

    private fun createButtonDisplay(text: String): TextDisplay {
        val display = viewer.world.spawnEntity(
            pivot.clone().add(0.0, -textDisplays.size * step, 0.0),
            EntityType.TEXT_DISPLAY
        ) as TextDisplay
        display.isVisibleByDefault = false
        viewer.showEntity(instance.plugin, display)
        display.transformation = Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
        display.text = text
        display.billboard = Display.Billboard.CENTER
        return display
    }

    private fun updateSelection() {
        textDisplays.keys.forEach { display -> display.backgroundColor = defaultColor }
        textDisplays.keys.toList().getOrNull(index)?.backgroundColor = selectedColor
    }

    /**
     * Вызывает функцию, связанную с текущим выбранным элементом.
     */
    fun invokeSelected() {
        textDisplays.keys.toList().getOrNull(index)?.let { display ->
            textDisplays[display]?.invoke()
        }
    }

    /**
     * Уничтожает GUI и освобождает ресурсы.
     */
    fun destroy() {
        openedMenuList.remove(this)
        textDisplays.keys.forEach(TextDisplay::remove)
        textDisplays.clear()
    }

    /**
     * Циклический индекс для навигации по доступным строкам.
     */
    private fun cyclicIndex(value: Int): Int {
        return when {
            value >= textDisplays.size -> 0
            value < 0 -> textDisplays.size - 1
            else -> value
        }
    }

}