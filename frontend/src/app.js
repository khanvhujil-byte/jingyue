import { api } from './api.js';
import { storage, currentMode, token, tokenKey } from './storage.js';
import { OrderTracker } from './orders.js';
import { icon } from './icons.js';
import { esc, price, cover, routeLink, empty, sectionHead, placeCard, journalCard, ticketCard, orderLabel } from './view.js';

const app = document.querySelector('#app');
const state = { user: null, tracker: null, places: [], categories: [], journals: [], tickets: [], currentPlace: null, journal: null, likers: [], following: false, busy: false, error: '', page: 1, exhausted: false, search: '', category: '', view: 'all', signCount: 0 };
let version = 0, controller, toastTimer, codeTimer, cooldown = 0;
const route = () => (location.hash.slice(1) || '/').split('?')[0];
const isLoggedIn = () => !!state.user && !!token();
const active = path => path === '/' ? route() === '/' : route().startsWith(path);
const nav = () => [['/', '发现山海'], ['/places', '探索目的地'], ['/journals', '旅行手记'], ['/orders', '我的票夹']].map(([path, name]) => routeLink(path, name, active(path) ? 'active' : '')).join('');
function toast(message, type = '') {
  const node = document.querySelector('#toast');
  node.textContent = message;
  node.className = `toast visible ${type}`;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => node.classList.remove('visible'), 3800);
}
function loginRequired() {
  if (isLoggedIn()) return false;
  storage.set('shanhai:return', location.hash || '#/');
  location.hash = '/login';
  toast('登录后继续，山海等你出发');
  return true;
}
function installTracker() {
  state.tracker?.stop();
  state.tracker = state.user ? new OrderTracker(state.user.id, () => {
    if (route() === '/orders' || route().startsWith('/place/')) render();
  }, toast) : null;
  state.tracker?.resume();
}
function shell(content) {
  const demo = currentMode() === 'demo';
  return `<div class="announcement"><span>${demo ? '体验模式 · 虚构景点与模拟订单，不产生真实购票' : '真实接口模式 · 数据来自已连接的后端'}</span><button data-action="mode">${demo ? '连接真实服务' : '切换体验模式'} ${icon('arrow')}</button></div>
  <header class="site-header"><div class="header-inner"><a class="brand" href="#/" aria-label="景约首页"><img src="./assets/mark.svg" alt=""><span>景约<small>让每一次出发，都值得。</small></span></a><nav class="desktop-nav" aria-label="主导航">${nav()}</nav><div class="header-actions">${isLoggedIn() ? routeLink('/profile', `${icon('user')}<span>${esc(state.user.nickName || '我的景约')}</span>`, 'account-link') : routeLink('/login', `${icon('user')}<span>登录 / 注册</span>`, 'account-link')}${routeLink('/publish', `${icon('edit')}<span>写游记</span>`, 'button outline compact')}</div></div><nav class="mobile-nav" aria-label="手机导航">${nav()}</nav></header>
  <main id="main" tabindex="-1">${content}</main>
  <footer class="site-footer"><div><a class="brand" href="#/"><img src="./assets/mark.svg" alt=""><span>景约</span></a><p>去看山，去听海，去遇见日常以外的风景。</p></div><div class="footer-links">${routeLink('/places', '探索目的地')}${routeLink('/journals', '旅行手记')}${routeLink('/about', '关于平台')}</div><div class="footer-note">文旅票务平台 · 项目展示<br>指定活动票种限量抢购</div></footer>`;
}
function render() {
  const path = route();
  let html;
  if (state.busy) html = `<div class="loading-state"><span class="spinner"></span><h2>好风景，正在路上</h2><p>正在获取${currentMode() === 'demo' ? '体验' : '服务'}数据…</p></div>`;
  else if (state.error) html = empty('这次连接没有成功', state.error, '<button class="button primary" data-action="retry">重新加载</button>');
  else if (path === '/') html = home();
  else if (path === '/places') html = destinations();
  else if (path.startsWith('/place/')) html = placeDetail();
  else if (path === '/journals') html = journalList();
  else if (path.startsWith('/journal/')) html = journalDetail();
  else if (path === '/orders') html = orders();
  else if (path === '/login') html = login();
  else if (path === '/profile') html = profile();
  else if (path === '/publish') html = publish();
  else if (path === '/about') html = about();
  else html = empty('这条小路还没有开放', '回到首页，重新发现山海。', routeLink('/', '返回首页', 'button primary'));
  app.innerHTML = shell(html);
  app.querySelectorAll('img').forEach(img => img.addEventListener('error', () => { if (!img.dataset.fallback) { img.dataset.fallback = '1'; img.src = './assets/valley.svg'; } }, { once: true }));
}
function home() {
  return `<section class="hero-wrap container"><div class="hero"><div class="hero-copy"><span class="eyebrow light">不必远行，也有好风景</span><h1>把周末<br>交给<span>山海。</span></h1><p>从一段山路，到一场日落。<br>发现身边的目的地，让下一次出发简单一点。</p><a href="#/places" class="button cream">寻找我的下一站 ${icon('arrow')}</a><div class="hero-footnote"><span class="tiny-line"></span>每一段日常，都值得一次出走</div></div><div class="hero-art"><img src="./assets/hero.svg" alt="青绿山峦、湖泊与山间小屋的原创风景插画"><div class="hero-stamp">山海漫游计划<br><strong>就从这个周末开始</strong></div><div class="hero-location">${icon('pin')}山野之间，自有答案</div></div></div><form class="search-bar" id="search-form"><div class="search-location">${icon('pin')}<span>身边的山海<small>寻找周末目的地</small></span></div><label class="search-input">${icon('search')}<input name="name" aria-label="搜索景点或文旅场馆" placeholder="想去哪里？搜索景点、场馆名称"></label><button class="button primary" type="submit">发现好去处 ${icon('arrow')}</button></form></section>
    <section class="container categories-section"><div class="category-intro"><span class="eyebrow">按心情出发</span><h2>今天，想去哪里？</h2></div><div class="category-grid">${state.categories.slice(0, 6).map((c, i) => `<a href="#/places?type=${encodeURIComponent(c.id)}" class="category-item"><span class="category-icon tone-${i % 4}">${icon(c.icon || ['mountain', 'city', 'sun', 'book'][i % 4])}</span><span>${esc(c.name)}</span>${icon('arrow')}</a>`).join('')}</div></section>
    <section class="container section">${sectionHead('不赶路，只感受', '值得出发的目的地', routeLink('/places', '探索全部目的地 ' + icon('arrow'), 'text-link'))}<div class="place-grid">${state.places.slice(0, 4).map(placeCard).join('') || empty('目的地即将上新', '换个时间再来看看。')}</div></section>
    <section class="container"><div class="promo-banner"><span class="promo-illustration">${icon('ticket')}</span><div><span class="eyebrow">把好风景，装进口袋</span><h2>心动的目的地，也有心动的价格。</h2><p>精选活动票种，限量开放。选一处风景，为周末留个位置。</p></div>${routeLink('/places', '去看看活动票 ' + icon('arrow'), 'button primary')}</div></section>
    <section class="container section">${sectionHead('把沿途写成故事', '旅人的山海手记', routeLink('/journals', '阅读更多 ' + icon('arrow'), 'text-link'))}<div class="journal-grid">${state.journals.slice(0, 3).map(journalCard).join('') || empty('第一篇游记，等你来写', '分享你的沿途风景。')}</div></section>
    <section class="container promise-strip"><span>${icon('leaf')}慢一点，发现更多</span><span>${icon('ticket')}活动票种，一人限购一份</span><span>${icon('shield')}提交后可查询处理结果</span></section>`;
}
function destinations() {
  return `<section class="container page-heading"><span class="eyebrow">下一站，就去这里</span><h1>探索目的地<span class="heading-dot">。</span></h1><p>山野、海岸、街巷和展馆，总有一处风景刚好适合你。</p></section><section class="container"><form class="list-search" id="search-form"><label>${icon('search')}<input name="name" value="${esc(state.search)}" placeholder="搜索景点或文旅场馆" aria-label="目的地名称"></label><button class="button primary">搜索</button></form><div class="filter-row"><button class="filter-chip ${!state.category ? 'selected' : ''}" data-category="">全部目的地</button>${state.categories.map(c => `<button class="filter-chip ${String(c.id) === state.category ? 'selected' : ''}" data-category="${esc(c.id)}">${esc(c.name)}</button>`).join('')}</div><div class="results-heading"><span>${state.category ? esc(state.categories.find(c => String(c.id) === state.category)?.name || '分类结果') : state.search ? `“${esc(state.search)}”的搜索结果` : '每一种出发，都有回响'}</span><small>按服务返回顺序展示</small></div><div class="place-grid">${state.places.map(placeCard).join('') || empty('暂时没有找到目的地', '试试其他关键词或分类。')}</div>${moreButton()}</section>`;
}
function placeDetail() {
  const p = state.currentPlace;
  return `<section class="container detail-breadcrumb">${routeLink('/places', '探索目的地')}<span>/</span>${esc(p.name)}</section><section class="container place-detail"><div class="detail-visual">${cover(p.images, p.name)}<span class="image-label">${esc(p.area || '下一站风景')}</span></div><div class="detail-copy"><span class="eyebrow">给周末，一个新的去处</span><h1>${esc(p.name)}</h1><p class="detail-rating">等你发现的新风景</p><p class="description">${esc(p.description || '换个角度认识一处风景。开放安排、设施与游览须知，请以场馆实际公告为准。')}</p><div class="detail-facts"><p>${icon('pin')}<span>${esc(p.address)}</span></p><p>${icon('clock')}<span>${esc(p.openHours || '开放时间以场馆公告为准')}</span></p><p>${icon('ticket')}<span>参考价 ¥${esc(p.avgPrice || '—')} · 活动票价见下方</span></p></div><a href="#tickets" class="button primary" data-action="scroll-tickets">查看限量活动票 ${icon('arrow')}</a></div></section><section id="tickets" class="container section">${sectionHead('为这段风景，留一份期待', '限量活动票')}<div class="info-note">${icon('ticket')}指定活动票种限量抢购，每位用户每个票种限购一份。当前不提供入园日期、场次选择或支付核销。</div>${state.tickets.map(t => ticketCard(t, p, state.tracker?.get(t.id))).join('') || empty('这里的活动票还没开放', '你仍可以探索其他目的地。')}${state.tickets.length ? `<details class="rules"><summary>查看活动与使用说明</summary>${state.tickets.map(t => `<h4>${esc(t.title)}</h4><p>${esc(t.rules || '请以活动票种的实际规则为准。')}</p>`).join('')}<p>余量来自展示缓存，实际是否受理以提交结果为准。提交成功只代表进入处理流程，订单创建成功后才能在票夹看到对应订单号。</p></details>` : ''}</section>`;
}
function journalList() {
  return `<section class="container page-heading split-heading"><div><span class="eyebrow">风景之外，还有故事</span><h1>旅行手记<span class="heading-dot">。</span></h1><p>记下沿途的光，也读一读别人眼里的世界。</p></div>${routeLink('/publish', icon('edit') + '写一篇游记', 'button primary')}</section><section class="container"><div class="filter-row"><button data-view="all" class="filter-chip ${state.view === 'all' ? 'selected' : ''}">发现手记</button><button data-view="following" class="filter-chip ${state.view === 'following' ? 'selected' : ''}">关注的旅人</button></div><div class="journal-grid">${state.journals.map(journalCard).join('') || empty('这里还没有手记', state.view === 'following' ? '关注喜欢的作者后，再来看看他们的分享。' : '写下第一段山海故事吧。')}</div>${state.view === 'all' ? moreButton() : '<p class="muted center">展示当前一页关注动态。</p>'}</section>`;
}
function journalDetail() {
  const b = state.journal;
  return `<article class="container article-layout"><div class="article-main"><a class="text-link" href="#/journals">${icon('back')}返回旅行手记</a><span class="eyebrow">一段旅途，一份记录</span><h1>${esc(b.title)}</h1><div class="article-author"><span class="avatar">${esc((b.name || '旅人')[0])}</span><div><strong>${esc(b.name || '景约旅人')}</strong><small>分享沿途的好风景</small></div>${String(b.userId) !== String(state.user?.id) ? `<button class="button outline compact" data-action="follow">${state.following ? '已关注' : '关注旅人'}</button>` : ''}</div>${cover(b.images, b.title, 'article-cover')}<div class="article-text">${esc(b.content).replace(/\n/g, '<br>')}</div><div class="article-like"><button class="like-button detail-like ${b.isLike ? 'is-liked' : ''}" data-action="like" data-id="${esc(b.id)}" aria-pressed="${!!b.isLike}" ${b.likePending ? 'disabled' : ''}>${icon('heart')}<span>${Number(b.liked) || 0}</span><span>人喜欢这段旅程</span></button><p class="muted">${state.likers.length ? state.likers.map(u => esc(u.nickName || '景约旅人')).join('、') + ' 等旅人来过' : '喜欢这段记录，就点个赞吧。'}</p></div></div><aside class="article-aside"><span class="eyebrow">让故事成为下一次出发</span><h3>也想去看看？</h3><p>看看游记关联的目的地与活动票种。</p>${routeLink(`/place/${b.shopId}`, '探索这里 ' + icon('arrow'), 'button primary')}</aside></article>`;
}
function orders() {
  if (!isLoggedIn()) return loginEmpty('登录后，看看你的下一次出发', '票夹会记录当前浏览器会话提交的抢购，并向服务端查询真实处理结果。');
  const rows = state.tracker?.records || [];
  return `<section class="container page-heading"><span class="eyebrow">每一次期待，都有回音</span><h1>我的票夹<span class="heading-dot">。</span></h1><p>查看本次会话提交的活动票与订单处理结果。</p></section><section class="container orders-layout"><div>${rows.map(o => `<article class="order-card"><div class="order-card-head"><span>${icon('ticket')}${esc(o.placeName)}</span><span class="status-badge ${o.state === 'CREATED' ? 'success' : ''}" role="status">${orderLabel(o.state)}</span></div><div class="order-card-body"><div><h3>${esc(o.title)}</h3><p>提交于 ${esc(new Date(o.createdAt).toLocaleString('zh-CN'))}</p>${o.state === 'CREATED' ? `<p class="order-id">订单号 ${esc(o.orderId)}</p><p class="muted">订单已创建。支付与入园核销尚未接入。</p>` : '<p>正在确认结果，请勿重复提交同一票种。</p>'}</div><div><strong class="price">¥${price(o.price)}</strong><button class="button outline compact" data-action="refresh-order" data-id="${esc(o.ticketId)}" ${state.tracker.work.has(o.ticketId) ? 'disabled' : ''}>${state.tracker.work.has(o.ticketId) ? '查询中…' : '刷新结果'}</button></div></div></article>`).join('') || empty('票夹里，还缺一段风景', '从喜欢的目的地开始，看看有哪些限量活动票。', routeLink('/places', '去探索目的地 ' + icon('arrow'), 'button primary'))}</div><aside class="soft-panel"><span class="eyebrow">安心等待，不必重复</span><h3>下单进度怎么查看？</h3><ol><li>正在提交：请求已发出。</li><li>排队处理中：请求已受理。</li><li>订单已创建：可查看订单号。</li></ol><p>暂未确认不代表失败，稍后可继续查询。</p><p class="muted">当前后端没有历史订单列表接口；这里仅保留当前用户在本浏览器会话提交过的票种。</p></aside></section>`;
}
function loginEmpty(title, text) { return `<section class="container">${empty(title, text, routeLink('/login', '登录 / 注册 ' + icon('arrow'), 'button primary'))}</section>`; }
function login() {
  if (isLoggedIn()) return `<section class="container">${empty('你已登录，随时可以出发', state.user.nickName, routeLink('/profile', '前往我的景约', 'button primary'))}</section>`;
  return `<section class="container login-layout"><div class="login-art"><img src="./assets/hero.svg" alt="山海风景插画"><div><span class="eyebrow light">来景约，找回生活的节奏</span><h1>好风景，<br>值得一起出发。</h1></div></div><div class="login-panel"><span class="eyebrow">欢迎来到景约</span><h1>登录，开启下一段旅程</h1><p>未注册的手机号验证后将自动创建账号。</p><form id="login-form"><label>手机号码<input name="phone" type="tel" inputmode="numeric" autocomplete="tel" pattern="1[3-9][0-9]{9}" maxlength="11" placeholder="请输入手机号码" required></label><label for="login-code">验证码</label><div class="code-input"><input id="login-code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="请输入六位验证码" required><button class="text-link" type="button" data-action="send-code" ${cooldown ? 'disabled' : ''}>${cooldown ? `${cooldown}秒后重试` : '获取验证码'}</button></div><button type="submit" class="button primary wide">登录 / 注册 ${icon('arrow')}</button></form><p class="login-note">${currentMode() === 'demo' ? '体验模式：输入任意格式正确的手机号，验证码为 123456。不会发送短信。' : '本地后端暂未接入短信服务，验证码从开发环境日志获取。请勿将开发日志公开。'}</p></div></section>`;
}
function profile() {
  if (!isLoggedIn()) return loginEmpty('你的山海故事，从这里开始', '登录后写游记、关注旅人，查看活动票处理结果。');
  return `<section class="container section"><div class="profile-banner"><span class="avatar large">${esc((state.user.nickName || '旅人')[0])}</span><div><span class="eyebrow">欢迎回来，景约旅人</span><h1>${esc(state.user.nickName || '我的景约')}</h1><p>不赶路，也不辜负沿途的风景。</p></div><button class="button outline" data-action="logout">退出当前浏览器</button></div><div class="profile-actions">${routeLink('/orders', icon('ticket') + '<strong>我的票夹</strong><span>查看活动订单结果</span>', 'soft-panel')}${routeLink('/publish', icon('edit') + '<strong>写一篇游记</strong><span>把沿途写成故事</span>', 'soft-panel')}<button class="soft-panel" data-action="sign">${icon('sun')}<strong>旅人签到</strong><span>本月连续签到 ${state.signCount} 天</span></button></div>${sectionHead('我的旅途收藏册', '我写过的手记')}<div class="journal-grid">${state.journals.map(journalCard).join('') || empty('还没有留下旅途记录', '下一段好风景，记得分享。')}</div></section>`;
}
function publish() {
  if (!isLoggedIn()) return loginEmpty('先登录，再写下这段旅程', '每一篇手记，都与真实的作者身份关联。');
  return `<section class="container page-heading"><span class="eyebrow">让沿途的美好，有迹可循</span><h1>写一篇旅行手记<span class="heading-dot">。</span></h1></section><section class="container editor-layout"><form id="publish-form" class="editor-panel"><label>手记标题<input name="title" maxlength="100" placeholder="用一句话，记录这段旅程" required></label><label>关联目的地<select name="shopId" required><option value="">请选择这次去过的目的地</option>${state.places.map(p => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join('')}</select></label><label>旅途记录<textarea name="content" maxlength="1900" rows="10" placeholder="写下路线、感受，或者一段值得分享的小事…" required></textarea></label><label>封面图片<select name="images"><option value="./assets/valley.svg">山谷与溪流</option><option value="./assets/coast.svg">海岸与日落</option><option value="./assets/town.svg">街巷与树影</option><option value="./assets/gallery.svg">艺术与留白</option></select></label><button type="submit" class="button primary">发布手记 ${icon('arrow')}</button></form><aside class="soft-panel"><h3>认真记录，轻松分享</h3><p>写下你亲眼看到的风景，提供有用的出行建议。</p><p>请勿填写个人敏感信息。当前支持文字与内置插画封面，暂不开放图片上传和评论。</p><p>目的地选择展示后端返回的第一页；可先在探索页搜索确认目的地。</p></aside></section>`;
}
function about() {
  return `<section class="container page-heading"><span class="eyebrow">关于这段旅程</span><h1>景约<span class="heading-dot">。</span></h1><p>一个关于发现、记录与出发的文旅票务项目。</p></section><section class="container about-grid"><div class="soft-panel"><h2>发现风景，也连接旅人</h2><p>景点与场馆查询、旅行手记、点赞与关注、指定活动票种限量抢购，组成一段完整的出发体验。</p><p>这是基于黑马点评后端扩展的学习项目。体验模式中的场馆、票种和内容均为虚构样例，不代表真实机构、售票活动或商业合作。</p></div><div class="soft-panel"><h2>当前支持的票务范围</h2><p>指定活动票种限量抢购，每位用户对每个票种限购一份。提交后可查询订单创建结果。</p><p>暂未提供分日期预约、场次、座位、实名制、支付、退款和二维码核销。订单创建成功不表示已经付款或可以入园。</p></div></section>`;
}
function moreButton() { return state.exhausted ? '<p class="end-note">这段风景先看到这里，下一次出发再见。</p>' : '<div class="load-more"><button class="button outline" data-action="more">再发现一些 ' + icon('arrow') + '</button></div>'; }

async function load() {
  const key = ++version;
  controller?.abort(); controller = new AbortController();
  const signal = controller.signal, path = route();
  state.busy = !['/orders', '/login', '/about'].includes(path); state.error = ''; render();
  try {
    if (path === '/') {
      const [categories, places, journals] = await Promise.all([api.categories(), api.places({}, signal), api.journals(1, signal)]);
      if (key !== version) return;
      Object.assign(state, { categories, places, journals });
    } else if (path === '/places') {
      const params = new URLSearchParams(location.hash.split('?')[1] || '');
      state.search = params.get('q') || ''; state.category = params.get('type') || ''; state.page = 1;
      const [categories, places] = await Promise.all([api.categories(), api.places({ name: state.search, typeId: state.category }, signal)]);
      if (key !== version) return;
      Object.assign(state, { categories, places, exhausted: places.length === 0 });
    } else if (path.startsWith('/place/')) {
      const id = path.split('/').pop();
      const [place, tickets] = await Promise.all([api.place(id, signal), api.tickets(id, signal)]);
      if (key !== version) return;
      if (!place) throw new Error('没有找到这个目的地');
      state.currentPlace = place; state.tickets = tickets || [];
    } else if (path === '/journals') {
      if (state.view === 'following' && loginRequired()) return;
      const response = state.view === 'following' ? await api.feed() : await api.journals(1, signal);
      if (key !== version) return;
      state.journals = (state.view === 'following' ? response?.list : response) || [];
      state.page = 1; state.exhausted = state.journals.length === 0;
    } else if (path.startsWith('/journal/')) {
      const id = path.split('/').pop(), journal = await api.journal(id, signal);
      if (key !== version) return;
      state.journal = journal;
      state.likers = await api.likers(id);
      state.following = isLoggedIn() && String(journal.userId) !== String(state.user.id) ? await api.following(journal.userId) : false;
    } else if (path === '/profile' && isLoggedIn()) {
      const [journals, signCount] = await Promise.all([api.myJournals(), api.signCount()]);
      if (key !== version) return;
      state.journals = journals || []; state.signCount = signCount || 0;
    } else if (path === '/publish' && isLoggedIn()) {
      const places = await api.places({}, signal); if (key !== version) return; state.places = places;
    }
  } catch (error) {
    if (key !== version || error.name === 'AbortError') return;
    if (error.status === 401) { loginRequired(); return; }
    state.error = error.message;
  } finally { if (key === version) { state.busy = false; render(); } }
}

async function like(id) {
  if (loginRequired()) return;
  const b = [state.journal, ...state.journals].find(b => b && String(b.id) === String(id));
  if (!b || b.likePending) return;
  const before = { isLike: !!b.isLike, liked: Number(b.liked) || 0 };
  const target = !before.isLike;
  Object.assign(b, { isLike: target, liked: Math.max(0, before.liked + (target ? 1 : -1)), likePending: true });
  const update = () => {
    app.querySelectorAll(`[data-action="like"][data-id="${CSS.escape(String(id))}"]`).forEach(btn => {
      btn.classList.toggle('is-liked', b.isLike); btn.disabled = !!b.likePending;
      btn.setAttribute('aria-pressed', !!b.isLike); btn.setAttribute('aria-label', b.isLike ? '取消点赞' : '点赞');
      btn.querySelector('span').textContent = b.liked;
    });
  };
  update();
  app.querySelectorAll(`[data-action="like"][data-id="${CSS.escape(String(id))}"]`).forEach(btn => btn.classList.add('like-pop'));
  try { await api.like(id, target); }
  catch (error) { Object.assign(b, before); update(); toast(error.message, 'error'); }
  try { const fresh = await api.journal(id); b.isLike = !!fresh.isLike; b.liked = fresh.liked; }
  catch { toast('点赞结果暂未刷新，请稍后重新查看'); }
  finally { b.likePending = false; update(); }
}

app.addEventListener('click', async event => {
  const element = event.target.closest('button, a[data-action]');
  if (!element || element.disabled) return;
  const action = element.dataset.action;
  if (element.hasAttribute('data-category')) { location.hash = `/places${element.dataset.category ? '?type=' + encodeURIComponent(element.dataset.category) : ''}`; return; }
  if (element.dataset.view) { if (element.dataset.view === 'following' && loginRequired()) return; state.view = element.dataset.view; load(); return; }
  try {
    if (action === 'mode') {
      state.tracker?.stop(); state.tracker = null; state.user = null;
      storage.set('shanhai:mode', currentMode() === 'demo' ? 'live' : 'demo');
      location.hash = '/'; await restoreSession(); await load();
    } else if (action === 'retry') load();
    else if (action === 'like') await like(element.dataset.id);
    else if (action === 'scroll-tickets') { event.preventDefault(); document.querySelector('#tickets')?.scrollIntoView({ behavior: 'smooth' }); }
    else if (action === 'buy') {
      if (loginRequired()) return;
      const ticket = state.tickets.find(t => String(t.id) === element.dataset.id);
      if (ticket) await state.tracker.buy(ticket, state.currentPlace);
    } else if (action === 'refresh-order') state.tracker?.refresh(element.dataset.id);
    else if (action === 'logout') {
      // Backend /user/logout is unimplemented: clear local credentials honestly.
      storage.remove(tokenKey()); state.user = null; state.tracker?.stop(); state.tracker = null;
      location.hash = '/'; await load(); toast('已退出当前浏览器');
    } else if (action === 'sign') {
      element.disabled = true; await api.sign(); state.signCount = await api.signCount(); render(); toast('今天也要好好出发，签到成功');
    } else if (action === 'follow') {
      if (loginRequired()) return;
      element.disabled = true; await api.follow(state.journal.userId, !state.following); state.following = !state.following; render();
    } else if (action === 'send-code') {
      if (cooldown) return;
      const phone = document.querySelector('[name="phone"]');
      if (!/^1[3-9]\d{9}$/.test(phone.value)) { phone.focus(); toast('请输入正确的手机号码', 'error'); return; }
      element.disabled = true;
      await api.code(phone.value);
      toast(currentMode() === 'demo' ? '体验验证码：123456' : '验证码已生成，请在开发环境日志查看');
      cooldown = 60; updateCodeButton(); clearInterval(codeTimer);
      codeTimer = setInterval(() => { cooldown = Math.max(0, cooldown - 1); updateCodeButton(); if (!cooldown) clearInterval(codeTimer); }, 1000);
    } else if (action === 'more') {
      element.disabled = true; element.textContent = '正在发现…';
      const key = version, next = state.page + 1;
      const rows = route() === '/places' ? await api.places({ name: state.search, typeId: state.category, page: next }) : await api.journals(next);
      if (key !== version) return;
      const list = route() === '/places' ? 'places' : 'journals';
      const seen = new Set(state[list].map(x => String(x.id)));
      state[list].push(...(rows || []).filter(x => !seen.has(String(x.id))));
      state.page = next; state.exhausted = !rows?.length; render();
    }
  } catch (error) { toast(error.message, 'error'); if (element.isConnected) element.disabled = false; }
});
function updateCodeButton() { const btn = app.querySelector('[data-action="send-code"]'); if (btn) { btn.disabled = cooldown > 0; btn.textContent = cooldown ? `${cooldown}秒后重试` : '获取验证码'; } }
app.addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.target, values = Object.fromEntries(new FormData(form));
  if (form.id === 'search-form') {
    const next = `/places${values.name.trim() ? '?q=' + encodeURIComponent(values.name.trim()) : ''}`;
    if (location.hash === '#' + next) load(); else location.hash = next;
    return;
  }
  const button = form.querySelector('[type="submit"]');
  if (!button || button.disabled) return;
  button.disabled = true; const previous = button.innerHTML; button.textContent = '正在提交…';
  try {
    if (form.id === 'login-form') {
      const newToken = await api.login(values); storage.set(tokenKey(), newToken);
      state.user = await api.me(); installTracker();
      toast('欢迎回来，一起出发吧');
      const back = storage.get('shanhai:return', '#/'); storage.remove('shanhai:return');
      location.hash = back.startsWith('#/') && back !== '#/login' ? back : '#/';
    } else if (form.id === 'publish-form') {
      if (loginRequired()) return;
      const id = await api.publish({ ...values, shopId: Number(values.shopId) });
      toast('手记已发布'); location.hash = `/journal/${id}`;
    }
  } catch (error) { toast(error.message, 'error'); }
  finally { if (button.isConnected) { button.disabled = false; button.innerHTML = previous; } }
});
async function restoreSession() {
  if (!token()) return;
  try { state.user = await api.me(); installTracker(); }
  catch (error) { if (error.status === 401) storage.remove(tokenKey()); state.user = null; }
}
window.addEventListener('session-expired', () => {
  state.user = null; state.tracker?.stop(); state.tracker = null;
  toast('登录已过期，请重新登录', 'error');
  if (['/orders', '/profile', '/publish'].includes(route())) location.hash = '/login';
});
window.addEventListener('hashchange', () => { window.scrollTo(0, 0); load(); });
document.addEventListener('visibilitychange', () => {
  if (document.hidden) state.tracker?.stop();
  else if (state.user && token()) { installTracker(); render(); }
});
window.addEventListener('pagehide', () => state.tracker?.stop());
window.addEventListener('pageshow', event => { if (event.persisted && state.user) installTracker(); });
await restoreSession();
load();
