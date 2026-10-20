// Fictional sample destinations and ticket products; no claims of real venue affiliation.
export const categories = [
  { id: 91001, name: '山野自然', icon: 'mountain' }, { id: 91002, name: '城市人文', icon: 'city' },
  { id: 91003, name: '滨海漫游', icon: 'sun' }, { id: 91004, name: '艺术展馆', icon: 'book' }
];
export const places = [
  { id: 92001, typeId: 91001, name: '青岚山谷', area: '深圳 · 山野自然', address: '山海示范区 · 青岚山谷入口', images: './assets/valley.svg', score: 0, avgPrice: 68, openHours: '08:30—18:00', sold: 0, comments: 0, description: '沿着溪流走进山谷，把城市的喧闹留在身后。找一段不赶时间的路，和山风一起慢下来。', tag: '山风与溪流' },
  { id: 92002, typeId: 91003, name: '白屿海岸', area: '深圳 · 滨海漫游', address: '山海示范区 · 白屿海岸步道', images: './assets/coast.svg', score: 0, avgPrice: 49, openHours: '09:00—19:00', sold: 0, comments: 0, description: '一条通向海的步道，一片适合发呆的沙滩。傍晚来这里，等一场柔软的日落。', tag: '去海边散步' },
  { id: 92003, typeId: 91002, name: '南城旧巷', area: '深圳 · 城市人文', address: '山海示范区 · 南城文化街区', images: './assets/town.svg', score: 0, avgPrice: 39, openHours: '10:00—20:00', sold: 0, comments: 0, description: '白墙、瓦檐和巷口的树影，留住一座城缓慢的日常。适合独自走走，也适合带上朋友。', tag: '遇见旧时光' },
  { id: 92004, typeId: 91004, name: '山海艺术馆', area: '深圳 · 艺术展馆', address: '山海示范区 · 艺术中心', images: './assets/gallery.svg', score: 0, avgPrice: 58, openHours: '10:00—18:00', sold: 0, comments: 0, description: '光线穿过拱廊，艺术发生在日常之间。用一个下午，看看不同的世界。', tag: '给灵感留白' }
];
export const tickets = places.map((p, i) => ({
  id: 93001 + i, shopId: p.id, title: ['山谷漫游 · 单人活动票', '海岸慢行 · 单人活动票', '旧巷寻迹 · 单人活动票', '艺术漫游 · 单人活动票'][i],
  subTitle: '指定活动票种 · 每位用户限购一份', payValue: [3900, 2900, 1900, 3800][i], actualValue: p.avgPrice * 100,
  type: 1, status: 1, stock: [120, 80, 100, 60][i],
  beginTime: '2026-01-01T00:00:00', endTime: '2030-12-31T23:59:59',
  rules: '体验样例票种。活动期限为抢购受理期限，不代表可选入园日期；未接入支付、实名制或入园核销。'
}));
export const journals = [
  { id: 94001, shopId: 92001, userId: 95001, name: '林间来信', title: '把周末还给山野，走一条有风的路', images: './assets/valley.svg', content: '不必把每一个周末排满。\n沿着溪水慢慢走，听见树叶的声音，也听见自己。\n\n这次选择了青岚山谷，带一瓶水和一份好心情，就足够出发。请遵守景区规定，不离开开放步道。', liked: 0, isLike: false },
  { id: 94002, shopId: 92002, userId: 95002, name: '一颗海盐', title: '在海边等日落，是一件很正经的事', images: './assets/coast.svg', content: '在海岸走了很久，手机放进口袋，风吹过来的时候，整个下午都变轻了。\n\n白屿海岸是一组虚构的旅行灵感，愿你也找到自己的那片海。', liked: 0, isLike: false },
  { id: 94003, shopId: 92003, userId: 95001, name: '林间来信', title: '转进这条小巷，城市突然慢了下来', images: './assets/town.svg', content: '巷子里没有紧凑的打卡清单，只有瓦檐、树影和午后的光。\n\n看看建筑，坐一会儿，再慢慢往前走。', liked: 0, isLike: false }
];
