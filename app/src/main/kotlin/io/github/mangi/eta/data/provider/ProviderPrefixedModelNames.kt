package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

/**
 * 聚合网关把上游供应商放进模型 ID 的前缀：`vendor-a/model-x`。
 *
 * 远端 /models 的 display_name 只给模型名（有时干脆不给，退回 ID），同一网关下
 * 各家同款模型会撞成同一个名字，列表里分不清是谁家。拉取时把前缀补进展示名：
 * `Model X（vendor-a）`。
 *
 * 只作用于网关（源类型 CUSTOM）目录，官方服务商 ID 里的前缀是模型家族而非供应商
 * （如硅基流动 `Qwen/Qwen2.5-7B-Instruct`），保持原样。
 */
internal object ProviderPrefixedModelNames {
    private const val SUFFIX_OPEN = "（"
    private const val SUFFIX_CLOSE = "）"

    fun apply(provider: ProviderSetting, models: List<Model>): List<Model> =
        if (!isAggregator(provider)) models else models.map(::rename)

    private fun isAggregator(provider: ProviderSetting): Boolean =
        provider is CustomProviderSetting ||
            ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.CUSTOM

    private fun rename(model: Model): Model {
        val displayName = format(model.displayName, model.modelId)
        return if (displayName == model.displayName) model else model.copy(displayName = displayName)
    }

    internal fun format(displayName: String, modelId: String): String {
        val id = modelId.trim()
        val provider = providerPrefix(id) ?: return displayName
        val raw = displayName.trim().ifBlank { id }
        // 远端没给展示名时展示名等于原始 ID，先摘掉重复的供应商前缀。
        val base = raw.removePrefix("$provider/").ifBlank { id }
        val suffix = "$SUFFIX_OPEN$provider$SUFFIX_CLOSE"
        // 重复拉取同一个 ID 时结果不变，不会叠加后缀。
        return if (base.endsWith(suffix)) base else base + suffix
    }

    private fun providerPrefix(modelId: String): String? {
        val slash = modelId.indexOf('/')
        if (slash <= 0 || slash == modelId.lastIndex) return null
        val prefix = modelId.substring(0, slash)
        return prefix.takeIf { prefix.none(Char::isWhitespace) }
    }
}