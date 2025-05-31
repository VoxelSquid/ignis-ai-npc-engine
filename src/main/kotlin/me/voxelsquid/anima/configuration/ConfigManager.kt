package me.voxelsquid.anima.configuration

import me.voxelsquid.anima.Anima.Companion.ignisInstance
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ConfigManager(private val dataFolder: File = plugin.dataFolder) {

    private val replaceResources = ConfigurationAccessor(path = "core.replace-resources", defaultValue = true, comments = mutableListOf("By default, the configs responsible for game values are overwritten each time the server starts with the defaults.", "This is to make it easier for me to adjust the balance. But it can be switched off.")).get()

    lateinit var language:    YamlConfiguration
    lateinit var professions: YamlConfiguration
    lateinit var prices:      YamlConfiguration
    lateinit var prompts:     YamlConfiguration
    lateinit var races:       YamlConfiguration
    lateinit var skins:       YamlConfiguration

    init {
        this.saveResources()
        this.loadResources()
    }

    private fun saveResources() {
        resources.forEach { resource -> plugin.saveResource(resource, replaceResources) }
    }

    // Перевод плагина с помощью ИИ.
    private fun loadLanguage() {
        val languageFile = File(plugin.dataFolder, "language.yml")
        if (plugin.controller.translation) {
            plugin.bifrost.client.translate(languageFile) { translation ->
                plugin.logger.info("Ignis uses generative translation! The plugin has been automatically translated.")
                this.language = translation
            }
        } else {
            plugin.logger.info("Generative translation is disabled in the config.yml, Ignis uses standard language.yml.")
            this.language = YamlConfiguration.loadConfiguration(languageFile)
        }
    }

    private fun loadResources() {
        this.loadLanguage()
        professions = YamlConfiguration.loadConfiguration(File(dataFolder, "professions.yml"))
        prices      = YamlConfiguration.loadConfiguration(File(dataFolder, "prices.yml"))
        prompts     = YamlConfiguration.loadConfiguration(File(dataFolder, "prompts.yml"))
        races       = YamlConfiguration.loadConfiguration(File(dataFolder, "races.yml"))
        skins       = YamlConfiguration.loadConfiguration(File(dataFolder, "skins.yml"))
    }

    private companion object {
        val plugin    = ignisInstance
        val resources = listOf("language.yml", "professions.yml", "prices.yml", "prompts.yml", "races.yml", "skins.yml", "data.db")
    }

}