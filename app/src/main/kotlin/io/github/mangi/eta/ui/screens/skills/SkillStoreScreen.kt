package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.skill.SkillHubApi
import io.github.mangi.eta.agent.skill.SkillHubCategory
import io.github.mangi.eta.agent.skill.SkillHubSummary
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

/**
 * 技能商店的分类缓存。
 *
 * 分类是准静态数据，每次进商店都重新拉一遍纯属浪费——之前进店要先等分类、
 * 再等列表，串行两次往返，弱网下就是三十秒转圈。这里进程内缓存，
 * 只有首次或用户点重试时才真正请求。
 */
private object SkillStoreCache {
    @Volatile
    var categories: List<SkillHubCategory> = emptyList()
}

/** 技能商店：浏览 SkillHub 技能并一键安装，效果对齐 miniAI 的「技能商店」。 */
@Composable
internal fun SkillStoreScreen(
    onBack: () -> Unit,
    /** 安装成功后回调：让技能列表立刻刷新，返回时不必再等一次读盘。 */
    onSkillInstalled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf(SkillStoreCache.categories) }
    var skills by remember { mutableStateOf<List<SkillHubSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var installingSlug by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun load() {
        loading = true
        error = null
        // 连点分类/搜索时，上一次请求必须让位：否则先发的慢请求会把后发的结果盖回去。
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // 分类和列表互不依赖：并发发出，整体耗时等于较慢的那个，而不是两者相加。
                val loaded = coroutineScope {
                    val categoriesJob = async {
                        if (SkillStoreCache.categories.isNotEmpty()) {
                            SkillStoreCache.categories
                        } else {
                            runCatching { SkillHubApi.listCategories() }
                                .onSuccess { SkillStoreCache.categories = it }
                                .getOrDefault(emptyList())
                        }
                    }
                    val skillsJob = async {
                        SkillHubApi.listSkills(
                            page = 1,
                            pageSize = 50,
                            keyword = query.takeIf { it.isNotBlank() },
                            category = categoryFilter,
                            sortBy = "downloads",
                            order = "desc",
                        )
                    }
                    categoriesJob.await() to skillsJob.await()
                }
                categories = loaded.first
                skills = loaded.second
            } catch (failure: Throwable) {
                error = failure.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    // 进页面立即拉一次，搜索/分类切换由各自的点击动作触发，避免重复请求。
    LaunchedEffect(Unit) { load() }

    fun install(skill: SkillHubSummary) {
        if (installingSlug != null) return
        installingSlug = skill.slug
        notice = null
        scope.launch {
            try {
                val bytes = SkillHubApi.download(skill.slug)
                val result = withContext(Dispatchers.IO) {
                    val installer = SkillRuntime.createPackageInstaller(context.applicationContext)
                    installer.installLocalZip(
                        openStream = { ByteArrayInputStream(bytes) },
                    )
                }
                notice = when (result) {
                    is SkillInstallResult.Success -> "已安装 ${result.installed.size} 个技能"
                    is SkillInstallResult.Conflict -> "存在同名技能：${result.conflicts.joinToString { it.name }}"
                    is SkillInstallResult.Failure -> "安装失败：${result.error.code}"
                }
                if (result is SkillInstallResult.Success) {
                    // 装完立刻刷新技能列表，用户返回技能页看到的就是新状态。
                    onSkillInstalled()
                }
            } catch (failure: Throwable) {
                notice = "下载失败：${failure.message}"
            } finally {
                installingSlug = null
            }
        }
    }

    MiuixScaffoldPage(
        title = "技能商店",
        onBack = onBack,
    ) {
        item(key = "store-search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { load() },
                expanded = false,
                onExpandedChange = {},
                label = "搜索技能",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(top = 4.dp, bottom = 8.dp),
            )
        }
        item(key = "store-category") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = CardHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                item(key = "all") {
                    CategoryChip(
                        text = "全部",
                        selected = categoryFilter == null,
                        onClick = { categoryFilter = null; load() },
                    )
                }
                items(categories, key = { it.key }) { category ->
                    CategoryChip(
                        text = category.name,
                        selected = categoryFilter == category.key,
                        onClick = { categoryFilter = category.key; load() },
                    )
                }
            }
        }
        notice?.let { text ->
            item(key = "store-notice") {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
        if (loading && skills.isEmpty()) {
            item(key = "store-loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
        } else if (error != null && skills.isEmpty()) {
            item(key = "store-error") {
                ListEmptyState(
                    title = "加载失败",
                    summary = error,
                    icon = Icons.Rounded.Storefront,
                    action = { EtaTextButton(text = "重试", onClick = ::load) },
                )
            }
        } else if (skills.isEmpty()) {
            item(key = "store-empty") {
                ListEmptyState(
                    title = "没有找到匹配的技能",
                    summary = "换个关键词或切换分类再试",
                    icon = Icons.Rounded.Storefront,
                )
            }
        } else {
            item(key = "store-list-title") { EtaPreferenceGroupTitle("共 ${skills.size} 个技能") }
            item(key = "store-list") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    skills.forEachIndexed { index, skill ->
                        if (index > 0) EtaPreferenceDivider()
                        val installing = installingSlug == skill.slug
                        EtaPreference(
                            title = skill.name,
                            summary = "${formatDownloads(skill.downloads)} · ${skill.displayDescription.take(60)}",
                            enabled = !installing,
                            endActions = {
                                if (installing) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                } else {
                                    EtaTextButton(text = "安装", onClick = { install(skill) })
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatDownloads(downloads: Int): String = when {
    downloads >= 10_000 -> "${downloads / 1_000}k 次下载"
    downloads > 0 -> "$downloads 次下载"
    else -> "暂无下载"
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .squircleSurface(
                color = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
