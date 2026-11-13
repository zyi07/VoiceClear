package com.voiceclear.app.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * 朗读提示句。
 *
 * 对应 tools/prompts/prompts.json，字段与之一致，便于导出时交叉核对。
 */
data class CapturePrompt(
    val id: String,
    val category: String,
    val text: String,
    val note: String,
)

/**
 * 提示句库加载器。
 *
 * 句库随 App 打包（放在 assets），保证离线可用且版本固定——
 * 评测需要可复现的提示集合，线上动态下载会让历史数据失去可比性。
 */
object PromptLibrary {

    const val ASSET_PATH = "prompts/prompts.json"
    const val VERSION = "v1.0"

    /** 提示类别，用于采集时分组与评测时分层。 */
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

    /** 校验句库是否覆盖全部预期类别，用于启动时的完整性检查。 */
    fun missingCategories(prompts: List<CapturePrompt>): List<String> {
        val present = prompts.map { it.category }.toSet()
        return CATEGORIES.filterNot { present.contains(it) }
    }

    /** 按类别分组，便于采集时逐类进行。 */
    fun groupByCategory(prompts: List<CapturePrompt>): Map<String, List<CapturePrompt>> {
        return prompts.groupBy { it.category }
    }
}
