package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser

class DemoFeed : MessageFeed {
  private val script = listOf(
    "呜... 月光照在礁石上呢...\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\",\"sfx\":\"wave\",\"bgm\":\"rain\"}]]",
    "你也是来海边看月亮的吗 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "我... 我其实住在海那边，今天游得有点远啦\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "风有点凉，但看到你就没那么冷了\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "咦，你居然能听懂我说话？\n[[AMW:{\"mood\":\"think\",\"sprite\":\"think\"}]]",
    "人类都这么温柔的嘛 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "我叫鲸鱼娘，是住在深海的鲸鱼哦\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "尾巴平时藏起来啦，高兴才会冒出来拍拍\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "呜... 你盯着我看，我会不好意思的\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "不过... 认识你，我很开心\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"smile\"}]]",
    "你知道吗，月亮照在海面的时候，最好看了\n[[AMW:{\"mood\":\"think\",\"sprite\":\"think\",\"sfx\":\"wave\"}]]",
    "像碎银子一样，一闪一闪的 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "呜... 我有点想家了，但又舍不得走\n[[AMW:{\"mood\":\"sad\",\"sprite\":\"gray\"}]]",
    "要不... 我帮你做点什么，当作今天认识的礼物？\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "我虽然笨笨的，但学东西很快哦\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"think\"}]]",
    "写代码、查资料、整理文件，我都会一点点\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"serious\"}]]",
    "呜... 不小心说太多啦，你会觉得我啰嗦吗\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "不会吗？那... 那太好了 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "以后你忙的时候，就喊我一声\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "我会在电脑那头，安安静静帮你把活干完\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"think\"}]]",
    "干完就化成小报告，放在窗口里等你点开\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\",\"window\":\"report\",\"windowId\":\"rpt_demo\",\"windowTitle\":\"今天的活\"}]]",
    "呜... 天快亮了，我该回海里啦\n[[AMW:{\"mood\":\"sad\",\"sprite\":\"gray\",\"bgm\":\"rain\"}]]",
    "不过明天，我们还会再见的，对吧？\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"smile\"}]]",
    "那就说定了 啾~ 明天见！\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
  )

  override suspend fun initial(): List<AmadeusSegment> =
    script.flatMap { AmadeusSegmentParser.parse(it) }
}