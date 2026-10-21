package com.xingmu.tv.data

/**
 * 点播一级分类的“规范 typeId”：UI 只传这四个值，由源仓库按各源 categories 映射成真实分类。
 * 取值与主源 bfzy1 的分类号一致（20/30/39/45），便于与 docs/PLAN_ARCHITECTURE.md 对照。
 */
object CanonicalCategory {
    const val MOVIE = 20
    const val TV = 30
    const val ANIME = 39
    const val VARIETY = 45

    /** 海外一级入口（UI 用它进入海外分区选择页，本身不对应具体分类）。 */
    const val OVERSEAS_ROOT = 0

    /** 海外子分类使用独立的规范 id（90+），避免和各站真实分类号冲突。 */
    const val OVERSEAS_KOREA = 90
    const val OVERSEAS_US = 91
    const val OVERSEAS_JAPAN = 92
    const val OVERSEAS_THAI = 93
    const val OVERSEAS_HK_TW = 94
    const val OVERSEAS_VARIETY = 95
    const val OVERSEAS_ANIME = 96

    data class Section(
        val id: Int,
        val label: String,
        val aliases: List<String>,
        val categoryKey: String,
    )

    val overseas: List<Section> = listOf(
        Section(OVERSEAS_KOREA, "韩剧", listOf("韩国剧", "韩剧"), "TV"),
        Section(OVERSEAS_US, "美剧", listOf("欧美剧", "美剧", "美国剧"), "TV"),
        Section(OVERSEAS_JAPAN, "日剧", listOf("日本剧", "日剧"), "TV"),
        Section(OVERSEAS_THAI, "泰剧", listOf("泰国剧", "泰剧"), "TV"),
        Section(OVERSEAS_HK_TW, "港台剧", listOf("香港剧", "台湾剧", "港澳剧"), "TV"),
        Section(OVERSEAS_VARIETY, "海外综艺", listOf("欧美综艺", "日韩综艺", "港台综艺"), "VARIETY"),
        Section(OVERSEAS_ANIME, "海外动漫", listOf("日韩动漫", "欧美动漫", "海外动漫"), "ANIME"),
    )

    fun labelOf(typeId: Int): String? = when (typeId) {
        MOVIE -> "电影"
        TV -> "剧集"
        ANIME -> "动漫"
        VARIETY -> "综艺"
        else -> overseas.firstOrNull { it.id == typeId }?.label
    }

    fun isOverseas(typeId: Int): Boolean = typeId >= 90
}
