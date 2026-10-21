package com.tvbox.app.search

import com.tvbox.app.data.VodSummary

/**
 * 离线首拼的“候选种子”和热搜词（不冒充真实数据）：
 *  - 只用于在**尚未联网/尚未浏览**时也能演示 lldq → 流浪地球 的本地首拼；
 *  - 卡片带 sourceId="seed"，点击后会用中文片名走 API 搜索核对，不会直接进详情；
 *  - 真实浏览/搜索过的片名会持续加入 [PinyinIndex]，优先于种子。
 *
 * 若后续加 Room+FTS4，本对象可退化为“热词表”。
 */
object SearchSeeds {

    val hotWords: List<String> = listOf(
        "流浪地球", "庆余年", "狂飙", "漫长的季节", "三体", "繁花",
        "灌篮高手", "名侦探柯南", "奔跑吧", "歌手",
    )

    private data class Seed(val title: String, val year: String, val typeName: String)

    private val seeds: List<Seed> = listOf(
        Seed("流浪地球", "2019", "电影"),
        Seed("流浪地球2", "2023", "电影"),
        Seed("漫长的季节", "2023", "剧集"),
        Seed("庆余年", "2019", "剧集"),
        Seed("庆余年第二季", "2024", "剧集"),
        Seed("狂飙", "2023", "剧集"),
        Seed("三体", "2023", "剧集"),
        Seed("繁花", "2023", "剧集"),
        Seed("长安三万里", "2023", "动漫"),
        Seed("灌篮高手", "2022", "动漫"),
        Seed("名侦探柯南", "1996", "动漫"),
        Seed("奔跑吧", "2014", "综艺"),
        Seed("歌手", "2024", "综艺"),
        Seed("与凤行", "2024", "剧集"),
        Seed("墨雨云间", "2024", "剧集"),
        Seed("玫瑰的故事", "2024", "剧集"),
        Seed("看不见影子的少年", "2024", "剧集"),
        Seed("狐妖小红娘", "2015", "动漫"),
        Seed("斗罗大陆", "2021", "动漫"),
        Seed("鬼灭之刃", "2019", "动漫"),
        Seed("热辣滚烫", "2024", "电影"),
        Seed("飞驰人生2", "2024", "电影"),
        Seed("第二十条", "2024", "电影"),
        Seed("周处除三害", "2023", "电影"),
        Seed("沙丘2", "2024", "电影"),
        Seed("奥本海默", "2023", "电影"),
        Seed("韩剧请和我老公结婚", "2024", "韩国剧"),
        Seed("眼泪女王", "2024", "韩国剧"),
        Seed("美剧最后生还者", "2023", "欧美剧"),
        Seed("日剧昼颜", "2014", "日本剧"),
    )

    fun summaries(): List<VodSummary> = seeds.mapIndexed { index, seed ->
        VodSummary(
            vodId = "seed:$index",
            sourceId = SOURCE_ID,
            name = seed.title,
            pic = "",
            remarks = "离线候选",
            year = seed.year,
            typeId = "",
            typeName = seed.typeName,
            score = "",
            sourceCount = 1,
        )
    }

    const val SOURCE_ID = "seed"
}
