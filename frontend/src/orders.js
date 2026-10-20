import { api } from './api.js';
import { storage, orderKey } from './storage.js';

export class OrderTracker {
  constructor(userId, onChange, onMessage = () => {}) {
    this.key = orderKey(userId);
    this.records = storage.get(this.key, []);
    if (!Array.isArray(this.records)) this.records = [];
    this.onChange = onChange;
    this.onMessage = onMessage;
    this.work = new Map();
    this.busy = new Set();
    this.stopped = false;
  }
  persist() { storage.set(this.key, this.records); if (!this.stopped) this.onChange(); }
  get(id) { return this.records.find(x => String(x.ticketId) === String(id)); }
  async buy(ticket, place) {
    if (this.stopped || this.busy.has(String(ticket.id))) return;
    if (this.get(ticket.id)) return; // Explicit refresh is GET only.
    const item = { ticketId: String(ticket.id), title: ticket.title, placeId: String(place.id), placeName: place.name, price: ticket.payValue, state: 'SUBMITTING', createdAt: new Date().toISOString() };
    this.records.unshift(item);
    this.busy.add(item.ticketId);
    this.persist(); // Persist before POST: refresh during submission must not repost.
    try {
      await api.buy(item.ticketId);
      if (this.stopped) return;
      item.state = 'PENDING';
    } catch (error) {
      if (this.stopped) return;
      if (error.definitive && error.message !== '重复下单') {
        this.records = this.records.filter(x => x !== item);
        this.persist();
        this.onMessage(error.message, 'error');
        return;
      }
      item.state = 'UNKNOWN';
      this.onMessage('提交结果暂未确认，正在查询，请勿重复抢购');
    } finally { this.busy.delete(item.ticketId); }
    if (this.stopped) return;
    this.persist();
    this.poll(item);
  }
  resume() {
    for (const item of this.records) {
      // Revalidate local history; local storage never establishes server success.
      item.state = 'UNKNOWN';
      this.poll(item);
    }
  }
  poll(item) {
    if (this.stopped || this.work.has(item.ticketId)) return;
    const task = { timer: null, deadline: Date.now() + 60000, delay: 1000 };
    this.work.set(item.ticketId, task);
    const next = () => {
      if (this.stopped) return;
      task.timer = setTimeout(check, task.delay + Math.floor(Math.random() * 300));
    };
    const check = async () => {
      if (this.stopped) return;
      try {
        const result = await api.order(item.ticketId);
        if (this.stopped) return;
        if (result.state === 'CREATED') {
          item.state = 'CREATED';
          item.orderId = String(result.orderId);
          this.work.delete(item.ticketId);
          this.persist();
          this.onMessage('订单已创建，可在我的票夹查看订单');
          return;
        }
        item.state = result.state === 'PENDING' ? 'PENDING' : 'UNKNOWN';
      } catch (error) {
        if (this.stopped) return;
        if (error.status === 401) { this.work.delete(item.ticketId); item.state = 'WAITING'; this.persist(); return; }
      }
      if (Date.now() >= task.deadline) { item.state = 'WAITING'; this.work.delete(item.ticketId); this.persist(); return; }
      this.persist();
      next();
      task.delay = Math.min(task.delay * 2, 5000);
    };
    // The order may already be committed. Check now; only pending results back off.
    void check();
  }
  refresh(id) {
    const item = this.get(id);
    if (item && !this.work.has(item.ticketId)) { item.state = 'UNKNOWN'; this.persist(); this.poll(item); }
  }
  stop() { this.stopped = true; for (const task of this.work.values()) clearTimeout(task.timer); this.work.clear(); }
}
