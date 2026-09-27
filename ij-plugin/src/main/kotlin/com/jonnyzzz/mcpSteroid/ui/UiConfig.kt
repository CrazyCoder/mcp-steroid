/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.ide.SearchTopHitProvider
import com.intellij.ide.ui.OptionsSearchTopHitProvider
import com.intellij.ide.ui.search.BooleanOptionDescription
import com.intellij.ide.ui.search.OptionDescription
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.impl.stores.stateStore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.advanced.AdvancedSettingBean
import com.intellij.openapi.options.advanced.AdvancedSettingType
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.util.xmlb.XmlSerializer
import com.jonnyzzz.mcpSteroid.server.UiStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import java.util.MissingResourceException

/**
 * The get and set steps: IDE and project configuration read and changed through the platform's own models, with no
 * dialog and no compiled script. Each kind of setting keeps its own store:
 *
 * - `registry`: a registry key (Help | Find Action | Registry…)
 * - `advanced`: an advanced setting (Settings | Advanced Settings), by id
 * - `option`: an on/off option as Search Everywhere lists and toggles it, covering most Settings checkboxes of the IDE
 *   and the project
 * - `inspection`: an inspection of the project's current profile, by short name: on, off or a severity
 * - `component` with `field`: an option of a persistent settings component, by its state name, as it is saved in
 *   the settings XML
 *
 * A set reports the value before and after, so a cleanup step can put it back. The result of a set carries the value
 * a recording replays: the full option name, the canonical key.
 */
internal class UiConfig(private val project: Project) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** What a get or set step did, and the step's portable name for the setting, when it differs from the one given. */
    class Outcome(val line: String, val option: String? = null)

    suspend fun get(step: UiStep): Outcome = when {
        step.registry != null -> Outcome("registry ${step.registry} = ${registryValue(step.registry!!).asString()}")
        step.advanced != null -> advancedBean(step.advanced!!).let { Outcome("advanced ${it.id} = ${advancedValue(it)} (${it.type()})") }
        step.option != null -> getOption(step.option!!)
        step.inspection != null -> Outcome(inspectionState(step.inspection!!))
        step.log != null -> Outcome(UiLogs.level(step.log!!))
        else -> Outcome(getComponent(step.component!!, step.field))
    }

    suspend fun set(step: UiStep): Outcome {
        val value = step.value!!
        return when {
            step.registry != null -> {
                val rv = registryValue(step.registry!!)
                val before = rv.asString()
                withContext(edtAny) { rv.setValue(value) }
                Outcome("registry ${step.registry}: $before -> ${rv.asString()}")
            }
            step.advanced != null -> {
                val bean = advancedBean(step.advanced!!)
                val before = advancedValue(bean)
                val type = bean.type()
                val parsed: Any = when (type) {
                    AdvancedSettingType.Bool -> bool(value)
                    AdvancedSettingType.Int -> value.toIntOrNull() ?: throw UiStepFailure("advanced ${bean.id} takes a whole number, not \"$value\"")
                    AdvancedSettingType.String -> value
                    AdvancedSettingType.Enum -> {
                        val constants = (bean.defaultValueObject as Enum<*>).declaringJavaClass.enumConstants
                        constants.firstOrNull { it.name.equals(value, ignoreCase = true) }
                            ?: throw UiStepFailure("advanced ${bean.id} takes one of ${constants.joinToString { it.name }}, not \"$value\"")
                    }
                }
                withContext(edtAny) { advanced().setSetting(bean.id, parsed, type) }
                Outcome("advanced ${bean.id}: $before -> ${advancedValue(bean)}")
            }
            step.option != null -> setOption(step.option!!, bool(value))
            step.inspection != null -> Outcome(setInspection(step.inspection!!, value))
            step.log != null -> Outcome(UiLogs.setLevel(step.log!!, value))
            else -> Outcome(setComponent(step.component!!, step.field!!, value))
        }
    }

    // ---- registry and advanced settings

    private fun registryValue(key: String) = Registry.get(key).also {
        try {
            it.asString()
        } catch (e: MissingResourceException) {
            val similar = Registry.getAll().map { v -> v.key }.filter { k -> k.contains(key, ignoreCase = true) || key.contains(k, ignoreCase = true) }
                .sortedBy { k -> k.length }.take(5)
            throw UiStepFailure("unknown registry key $key" + if (similar.isEmpty()) "" else "; similar keys: ${similar.joinToString()}")
        }
    }

    private fun advancedBean(id: String): AdvancedSettingBean {
        val beans = AdvancedSettingBean.EP_NAME.extensionList
        return beans.firstOrNull { it.id == id } ?: run {
            val similar = beans.map { it.id }.filter { it.contains(id, ignoreCase = true) }.sortedBy { it.length }.take(5)
            throw UiStepFailure("unknown advanced setting $id" + if (similar.isEmpty()) "" else "; similar ids: ${similar.joinToString()}")
        }
    }

    private fun advanced(): AdvancedSettingsImpl = AdvancedSettings.getInstance() as AdvancedSettingsImpl

    /** The setting's value; `getSetting` is protected, and the typed getters need the enum class at compile time. */
    private fun advancedValue(bean: AdvancedSettingBean): String =
        AdvancedSettingsImpl::class.java.getDeclaredMethod("getSetting", String::class.java).apply { isAccessible = true }
            .invoke(advanced(), bean.id).let { (it as? Enum<*>)?.name ?: it.toString() }

    // ---- Search Everywhere options

    private fun options(): List<OptionDescription> {
        val out = mutableListOf<OptionDescription>()
        SearchTopHitProvider.EP_NAME.extensionList.filterIsInstance<OptionsSearchTopHitProvider.ApplicationLevelProvider>()
            .forEach { p -> runCatching { out += p.options } }
        PROJECT_OPTIONS.extensionList.forEach { p -> runCatching { out += p.getOptions(project) } }
        return out.filter { !it.option.isNullOrBlank() }
    }

    /** The options [wanted] names: those whose name is [wanted], else those that contain it. */
    private fun optionMatches(wanted: String, all: List<OptionDescription>): List<OptionDescription> {
        val exact = all.filter { o -> o.option.orEmpty().let { it.equals(wanted, ignoreCase = true) || it.trimEnd(':').equals(wanted, ignoreCase = true) } }
        return exact.ifEmpty { all.filter { it.option.orEmpty().contains(wanted, ignoreCase = true) } }.distinctBy { it.option to it.configurableId }
    }

    private fun describe(o: OptionDescription): String = buildString {
        append('"').append(o.option).append('"')
        append(" = ").append((o as? BooleanOptionDescription)?.isOptionEnabled?.toString() ?: "(not an on/off option)")
        o.configurableId?.let { append(" [settings page ").append(it).append(']') }
    }

    private suspend fun getOption(wanted: String): Outcome {
        val matches = withContext(edtAny) { optionMatches(wanted, options()).map { describe(it) } }
        if (matches.isEmpty()) throw UiStepFailure("no option matches \"$wanted\"; options are named as Search Everywhere lists them")
        return Outcome("${matches.size} option(s) match \"$wanted\":\n" + matches.take(MAX_LISTED).joinToString("\n") { "  $it" } +
            if (matches.size > MAX_LISTED) "\n  +${matches.size - MAX_LISTED} more: use a longer name" else "")
    }

    private suspend fun setOption(wanted: String, value: Boolean): Outcome = withContext(edtAny) {
        val matches = optionMatches(wanted, options()).filterIsInstance<BooleanOptionDescription>()
        val option = when (matches.size) {
            0 -> throw UiStepFailure("no on/off option matches \"$wanted\"; get lists the options by part of their name")
            1 -> matches.single()
            else -> throw UiStepFailure(
                "${matches.size} options match \"$wanted\"; pass the full name: " + matches.take(MAX_LISTED).joinToString("; ") { describe(it) }
            )
        }
        val before = option.isOptionEnabled
        option.setOptionState(value)
        Outcome("option \"${option.option}\": $before -> ${option.isOptionEnabled}", option = option.option)
    }

    // ---- inspections

    private fun profile() = InspectionProjectProfileManager.getInstance(project).currentProfile

    private fun inspectionKey(shortName: String): HighlightDisplayKey {
        val profile = profile()
        HighlightDisplayKey.find(shortName)?.takeIf { profile.getInspectionTool(shortName, project) != null }?.let { return it }
        val names = profile.getAllTools().map { it.tool.shortName }.distinct()
        val similar = names.filter { it.contains(shortName, ignoreCase = true) }.sortedBy { it.length }.take(5)
        throw UiStepFailure("no inspection with the short name $shortName in profile ${profile.name}" +
            if (similar.isEmpty()) "" else "; similar: ${similar.joinToString()}")
    }

    private suspend fun inspectionState(shortName: String): String = withContext(edtAny) {
        val key = inspectionKey(shortName)
        val profile = profile()
        val tool = profile.getInspectionTool(shortName, project)!!
        val on = profile.isToolEnabled(key)
        "inspection $shortName (\"${tool.displayName}\"): ${if (on) "on" else "off"}, ${profile.getErrorLevel(key, null).name} in profile ${profile.name}"
    }

    private suspend fun setInspection(shortName: String, value: String): String {
        val before = inspectionState(shortName)
        withContext(edtAny) {
            val key = inspectionKey(shortName)
            val profile = profile()
            when (value.lowercase()) {
                "on", "true", "enabled" -> profile.setToolEnabled(shortName, true, project)
                "off", "false", "disabled" -> profile.setToolEnabled(shortName, false, project)
                else -> {
                    val level = HighlightDisplayLevel.find(value.uppercase())
                        ?: throw UiStepFailure("an inspection takes on, off or a severity such as ERROR, WARNING, WEAK WARNING, INFORMATION; not \"$value\"")
                    profile.setToolEnabled(shortName, true, project)
                    profile.setErrorLevel(key, level, project)
                }
            }
            profile.profileChanged()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
        return "$before -> ${inspectionState(shortName).substringAfter(": ")}"
    }

    // ---- persistent settings components

    /** An initialized settings component by its state name, from the IDE's store first, then the project's. */
    private fun component(name: String): Pair<PersistentStateComponent<*>, String> {
        val stores = listOf("IDE" to ApplicationManager.getApplication().stateStore, "project" to project.stateStore)
        val known = mutableListOf<String>()
        for ((level, store) in stores) {
            val components = componentsOf(store)
            known += components.keys
            val info = components[name] ?: continue
            val component = info.javaClass.getMethod("getComponent").invoke(info)
            val state = component as? PersistentStateComponent<*> ?: throw UiStepFailure("$name keeps no state that get and set can read")
            return state to level
        }
        val similar = known.distinct().filter { it.contains(name, ignoreCase = true) }.sortedBy { it.length }.take(8)
        throw UiStepFailure("no loaded settings component $name" +
            (if (similar.isEmpty()) "" else "; similar: ${similar.joinToString()}") +
            ". A component loads when its feature or Settings page is first used")
    }

    /** `ComponentStoreImpl.getComponents()`: the store's components by state name. It is internal, hence reflection. */
    private fun componentsOf(store: Any): Map<String, Any> {
        val method = store.javaClass.methods.firstOrNull { it.name == "getComponents" && it.parameterCount == 0 } ?: return emptyMap()
        return (method.invoke(store) as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) -> v?.let { k.toString() to it } }.toMap()
    }

    private fun stateXml(component: PersistentStateComponent<*>): Element {
        val state = component.state ?: throw UiStepFailure("the component has no state yet")
        return (state as? Element)?.clone() ?: XmlSerializer.serialize(state)
    }

    /** The `<option name="field" value="..."/>` child of [xml], as the settings XML stores a bean's field. */
    private fun option(xml: Element, field: String): Element? = xml.getChildren("option").firstOrNull { it.getAttributeValue("name") == field }

    private suspend fun getComponent(name: String, field: String?): String = withContext(edtAny) {
        val (component, level) = component(name)
        val xml = stateXml(component)
        if (field == null) {
            val text = JDOMUtil.write(xml)
            "$level component $name (${component.javaClass.simpleName}); options that differ from the defaults:\n" +
                text.take(MAX_XML) + if (text.length > MAX_XML) "\n… ${text.length - MAX_XML} more characters" else ""
        } else {
            val option = option(xml, field)
            val value = option?.getAttributeValue("value") ?: option?.let { JDOMUtil.write(it) }
            "$level component $name: $field = ${value ?: "(the default: the field is not saved while it has its default value)"}"
        }
    }

    private suspend fun setComponent(name: String, field: String, value: String): String = withContext(edtAny) {
        val (component, level) = component(name)
        val xml = stateXml(component)
        val existing = option(xml, field)
        if (existing != null && existing.getAttribute("value") == null) {
            throw UiStepFailure("$name.$field holds structured XML, not a value; change it with a code step")
        }
        val before = existing?.getAttributeValue("value") ?: "(default)"
        (existing ?: Element("option").setAttribute("name", field).also { xml.addContent(it) }).setAttribute("value", value)
        @Suppress("UNCHECKED_CAST")
        val target = component as PersistentStateComponent<Any>
        val state = component.state
        val newState: Any = if (state is Element) xml else {
            val loaded = XmlSerializer.deserialize(xml, state!!.javaClass)
            // A field the bean does not have is dropped by the round trip: say so rather than report a change.
            if (option(XmlSerializer.serialize(loaded), field)?.getAttributeValue("value") != value && value != defaultOf(state, field)) {
                throw UiStepFailure("$name has no field $field that takes \"$value\"; get lists its saved fields")
            }
            loaded
        }
        target.loadState(newState)
        val after = option(stateXml(component), field)?.getAttributeValue("value") ?: "(default)"
        "$level component $name: $field $before -> $after (applied with loadState; windows open before may need reopening)"
    }

    /** The value [field] has in a fresh instance of [state]'s class, which the settings XML leaves out. */
    private fun defaultOf(state: Any, field: String): String? = runCatching {
        val fresh = state.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val f = generateSequence<Class<*>>(fresh.javaClass) { it.superclass }.firstNotNullOfOrNull { c -> runCatching { c.getDeclaredField(field) }.getOrNull() }
        f?.apply { isAccessible = true }?.get(fresh)?.toString()
    }.getOrNull()

    private fun bool(value: String): Boolean = when (value.lowercase()) {
        "true", "on", "yes" -> true
        "false", "off", "no" -> false
        else -> throw UiStepFailure("expected true or false, not \"$value\"")
    }

    private companion object {
        const val MAX_LISTED = 20
        const val MAX_XML = 4_000
        val PROJECT_OPTIONS = ExtensionPointName.create<OptionsSearchTopHitProvider.ProjectLevelProvider>("com.intellij.search.projectOptionsTopHitProvider")
    }
}
