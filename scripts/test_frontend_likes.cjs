// Run: node scripts/test_frontend_likes.cjs <deployed-or-staged-frontend-directory>
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = process.argv[2];
if (!root) throw new Error('请提供前端目录');
const tick = () => new Promise(resolve => setImmediate(resolve));

async function testPage(file) {
  let options, resolveWrite, rejectWrite;
  const puts = [], errors = [];
  let state = false, failRead = false;
  const axios = {
    put(url) {
      puts.push(url);
      return new Promise((resolve, reject) => {resolveWrite = resolve; rejectWrite = reject;});
    },
    get(url) {
      if (failRead) return Promise.reject(new Error('Network Error'));
      if (url.startsWith('/blog/likes/')) return Promise.resolve({data: []});
      return Promise.resolve({data: {id: 42, liked: state ? 7 : 6, isLike: state, images: 'a.jpg'}});
    }
  };
  const ctx = vm.createContext({Vue: function(o) {options = o;}, axios, util: {}});
  const html = fs.readFileSync(path.join(root, file), 'utf8');
  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    if (!/\bsrc\s*=/.test(match[1])) vm.runInContext(match[2], ctx, {filename: file});
  }
  assert.ok(options?.methods?.addLike);
  const instance = {...options.data, ...options.methods,
    $set: (obj, key, value) => obj[key] = value,
    $message: {error: msg => errors.push(msg)}, $nextTick: () => {},
    queryShopById: () => {}, queryLoginUser: () => {}};
  let blog = {id: 42, liked: 6, isLike: false};
  instance.blog = blog;
  const click = () => file === 'blog-detail.html' ? instance.addLike() : instance.addLike(blog);
  click(); click();
  assert.equal(puts.length, 1, '等待期间不能重复发送');
  assert.equal(puts[0], '/blog/like/42?liked=true');
  state = true; resolveWrite({data: true}); await tick();
  if (file === 'blog-detail.html') blog = instance.blog;
  assert.equal(blog.isLike, true); assert.equal(blog.liked, 7);
  assert.ok(!blog.likePending);
  click(); click();
  assert.equal(puts.length, 2);
  assert.equal(puts[1], '/blog/like/42?liked=false', '取消仍使用原PUT接口且false不会丢失');
  state = false; resolveWrite({data: false}); await tick();
  if (file === 'blog-detail.html') blog = instance.blog;
  assert.equal(blog.isLike, false); assert.equal(blog.liked, 6);
  click(); failRead = true; rejectWrite(new Error('Network Error')); await tick();
  assert.equal(blog.liked, 6, '网络失败不能凭空加赞');
  assert.ok(!blog.likePending, '失败后解除等待状态');
  assert.ok(errors.length >= 1 && errors.every(s => typeof s === 'string' && /[\u4e00-\u9fff]/.test(s)), '错误提示使用中文');
  console.log(file + ': 通过');
}
(async () => {
  for (const page of ['index.html', 'info.html', 'blog-detail.html']) await testPage(page);
  let rejectResponse;
  const axios = {defaults: {}, interceptors: {request: {use() {}}, response: {use(ok, bad) {rejectResponse = bad;}}}};
  const ctx = vm.createContext({axios, sessionStorage: {getItem() {}}, console: {log() {}}, setTimeout, location: {}});
  vm.runInContext(fs.readFileSync(path.join(root, 'js/common.js'), 'utf8'), ctx);
  await assert.rejects(rejectResponse(new Error('Network Error')), err => err === '服务器异常');
  console.log('网络异常中文提示：通过');
})().catch(error => {console.error(error); process.exitCode = 1;});
