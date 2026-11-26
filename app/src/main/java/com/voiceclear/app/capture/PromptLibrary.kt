package com.voiceclear.app.capture

import org.json.JSONObject

/**
 * 朗读提示句。
 *
 * 字段与 assets/tools 下对应句库一致，便于导出时交叉核对。
 */
data class CapturePrompt(
    val id: String,
    val category: String,
    val text: String,
    val note: String,
)

/**
 * 离线提示句库加载器。
 *
 * 普通话与山东方言采集使用独立文件、类别集合和版本元数据。句库随 App 打包，
 * 保证历史片段能按同一版本复现；profile 只选择语料，不改变 ASR 的 localeTag。
 */
object PromptLibrary {

    const val MANDARIN_ASSET_PATH = "prompts/prompts.json"
    const val SHANDONG_ASSET_PATH = "prompts/shandong.json"
    const val MANDARIN_VERSION = "v1.0"
    const val SHANDONG_VERSION = "v1.0"

    // 保留旧调用点的二进制/源码语义。
    const val ASSET_PATH = MANDARIN_ASSET_PATH
    const val VERSION = MANDARIN_VERSION

    /** 普通话提示类别，用于采集时分组与评测时分层。 */
    val CATEGORIES = listOf(
        "daily",
        "tone",
        "final",
        "retroflex",
        "dental_alveolar",
        "number",
        "negation",
        "proper_noun",
        "medical",
    )

    /** 山东方言句库覆盖语义类别，不把方言分区伪装成文本类别。 */
    val SHANDONG_CATEGORIES = listOf(
        "daily",
        "negation",
        "number",
        "direction",
        "medical",
        "dialogue",
    )

    fun assetPathFor(speechProfileId: String): String = when (speechProfileId) {
        "mandarin" -> MANDARIN_ASSET_PATH
        "shandong" -> SHANDONG_ASSET_PATH
        else -> throw IllegalArgumentException("未知 speechProfileId: $speechProfileId")
    }

    fun versionFor(speechProfileId: String): String = when (speechProfileId) {
        "mandarin" -> MANDARIN_VERSION
        "shandong" -> SHANDONG_VERSION
        else -> throw IllegalArgumentException("未知 speechProfileId: $speechProfileId")
    }

    fun categoriesFor(speechProfileId: String): List<String> = when (speechProfileId) {
        "mandarin" -> CATEGORIES
        "shandong" -> SHANDONG_CATEGORIES
        else -> throw IllegalArgumentException("未知 speechProfileId: $speechProfileId")
    }

    /**
     * 从 JSON 解析提示句库。
     *
     * 解析失败时返回空列表而不是崩溃——采集现场无法容忍应用崩溃。
     */
    fun parse(json: String): List<CapturePrompt> {
        return try {
            val root = JSONObject(json)
            val array = root.optJSONArray("prompts") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    if (id.isEmpty() || text.isEmpty()) continue
                    add(
                        CapturePrompt(
                            id = id,
                            category = item.optString("category", "daily"),
                            text = text,
                            note = item.optString("note", ""),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            emptyList()
        }
    }

    /** 读取句库自身声明的版本；损坏或缺字段时返回空串。 */
    fun parseVersion(json: String): String = try {
        JSONObject(json).optString("version", "")
    } catch (error: Exception) {
        ""
    }

    /** 校验当前 profile 句库是否覆盖全部预期类别。 */
    fun missingCategories(
        prompts: List<CapturePrompt>,
        speechProfileId: String = "mandarin",
    ): List<String> {
        val present = prompts.map { it.category }.toSet()
        return categoriesFor(speechProfileId).filterNot { present.contains(it) }
    }

    /** 按类别分组，便于采集时逐类进行。 */
    fun groupByCategory(prompts: List<CapturePrompt>): Map<String, List<CapturePrompt>> {
        return prompts.groupBy { it.category }
    }
}
