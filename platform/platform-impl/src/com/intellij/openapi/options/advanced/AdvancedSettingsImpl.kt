// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.options.advanced

import com.intellij.DynamicBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.util.text.nullize
import com.intellij.util.xmlb.annotations.Attribute
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.util.*

class AdvancedSettingBean : PluginAware {
  private var pluginDescriptor: PluginDescriptor? = null

  val enumKlass: Class<Enum<*>>? by lazy {
    @Suppress("UNCHECKED_CAST")
    if (enumClass.isNotBlank())
      (pluginDescriptor?.pluginClassLoader ?: javaClass.classLoader).loadClass(enumClass) as Class<Enum<*>>
    else
      null
  }

  override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
    this.pluginDescriptor = pluginDescriptor
  }

  @Attribute("id")
  @JvmField
  var id: String = ""

  @Attribute("default")
  @JvmField
  var defaultValue = ""

  @Attribute("titleKey")
  @JvmField
  var titleKey: String = ""

  @Attribute("groupKey")
  @JvmField
  var groupKey: String = ""

  @Attribute("descriptionKey")
  @JvmField
  var descriptionKey: String = ""

  @Attribute("bundle")
  @JvmField
  var bundle: String = ""

  @Attribute("enumClass")
  @JvmField
  var enumClass: String = ""

  fun type(): AdvancedSettingType {
    return when {
      enumClass.isNotBlank() -> AdvancedSettingType.Enum
      defaultValue.toIntOrNull() != null -> AdvancedSettingType.Int
      defaultValue == "true" || defaultValue == "false" -> AdvancedSettingType.Bool
      else -> AdvancedSettingType.String
    }
  }

  @Nls
  fun title(): String {
    return findBundle()?.getString(titleKey.ifEmpty { "advanced.setting.$id" }) ?: "!$id!"
  }

  @Nls
  fun group(): String? {
    if (groupKey.isEmpty()) return null
    return findBundle()?.getString(groupKey)
  }

  @Nls
  fun description(): String? {
    val descriptionKey = descriptionKey.ifEmpty { "advanced.setting.$id.description" }
    return findBundle()?.takeIf { it.containsKey(descriptionKey) }?.getString(descriptionKey)
  }

  private fun findBundle(): ResourceBundle? {
    val bundleName = bundle.nullize() ?: pluginDescriptor?.resourceBundleBaseName
                     ?: pluginDescriptor?.takeIf { it.pluginId.idString == "com.intellij" } ?.let { ApplicationBundle.BUNDLE }
                     ?: return null
    val classLoader = pluginDescriptor?.pluginClassLoader ?: javaClass.classLoader
    return DynamicBundle.INSTANCE.getResourceBundle(bundleName, classLoader)
  }

  companion object {
    val EP_NAME = ExtensionPointName.create<AdvancedSettingBean>("com.intellij.advancedSetting")
  }
}

@State(name = "AdvancedSettings", storages = [Storage(value = "other.xml")], reportStatistic = false)
class AdvancedSettingsImpl : AdvancedSettings(), PersistentStateComponent<AdvancedSettingsImpl.AdvancedSettingsState> {
  class AdvancedSettingsState {
    var settings = mutableMapOf<String, String>()
  }

  private var state: AdvancedSettingsState = AdvancedSettingsState()

  override fun getState(): AdvancedSettingsState {
    return state
  }

  override fun loadState(state: AdvancedSettingsState) {
    this.state = state
  }

  override fun setSetting(id: String, value: Any, expectType: AdvancedSettingType) {
    val (oldValue, type) = getSetting(id)
    if (type != expectType) {
      throw IllegalArgumentException("Setting type $type does not match parameter type $expectType")
    }
    state.settings[id] = if (expectType == AdvancedSettingType.Enum) (value as Enum<*>).name else value.toString()
    ApplicationManager.getApplication().messageBus.syncPublisher(AdvancedSettingsChangeListener.TOPIC).advancedSettingChanged(id, oldValue, value)
  }

  override fun getSettingString(id: String): String {
    val option = AdvancedSettingBean.EP_NAME.findFirstSafe { it.id == id } ?: throw IllegalArgumentException(
      "Can't find advanced setting $id")
    return state.settings[id] ?: option.defaultValue
  }

  override fun getSetting(id: String): Pair<Any, AdvancedSettingType> {
    val option = AdvancedSettingBean.EP_NAME.findFirstSafe { it.id == id } ?: throw IllegalArgumentException("Can't find advanced setting $id")
    val valueString = getSettingString(id)
    val value = when (option.type()) {
      AdvancedSettingType.Int -> valueString.toInt()
      AdvancedSettingType.Bool -> valueString.toBoolean()
      AdvancedSettingType.String -> valueString
      AdvancedSettingType.Enum -> try {
        java.lang.Enum.valueOf(option.enumKlass!!, valueString)
      }
      catch(e: IllegalArgumentException) {
        java.lang.Enum.valueOf(option.enumKlass!!, option.defaultValue)
      }
    }
    return value to option.type()
  }

  @TestOnly
  fun setSetting(id: String, value: Any, revertOnDispose: Disposable) {
    val (oldValue, type) = getSetting(id)
    setSetting(id, value, type)
    Disposer.register(revertOnDispose, Disposable { setSetting(id, oldValue, type )})
  }
}