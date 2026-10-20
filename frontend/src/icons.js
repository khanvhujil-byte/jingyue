const shapes = {
  arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>', search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
  pin: '<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 0 1 14 0Z"/><circle cx="12" cy="10" r="2"/>',
  mountain: '<path d="m2 19 8-14 5 8 3-5 5 11H2Zm5-9 3 2 3-2"/>',
  ticket: '<path d="M3 7h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4V7Zm12 0v2m0 3v2m0 3v2"/>',
  heart: '<path d="M20.5 5.5a5.3 5.3 0 0 0-7.5 0L12 6.6l-1.1-1.1a5.3 5.3 0 0 0-7.5 7.5L12 21l8.5-8a5.3 5.3 0 0 0 0-7.5Z"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  book: '<path d="M12 5C8 2 4 3 2 4v16c4-2 7-1 10 1 3-2 6-3 10-1V4c-2-1-6-2-10 1Zm0 0v16"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
  check: '<path d="m5 12 4 4L20 5"/>', close: '<path d="m6 6 12 12M18 6 6 18"/>',
  leaf: '<path d="M20 3C9 2 3 8 5 15c7 5 15-1 15-12ZM4 21 15 9"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 1v2m0 18v2M1 12h2m18 0h2M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2"/>',
  city: '<path d="M3 21V7h7v14m0-17h10v17M1 21h22M6 10v1m0 3v1m8-8h2m-2 4h2m-2 4h2"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>', back: '<path d="m14 5-7 7 7 7"/>',
  edit: '<path d="m16 3 5 5-12 12-6 1 1-6L16 3Zm-2 2 5 5"/>',
  shield: '<path d="M12 2 3 6v6c0 6 9 10 9 10s9-4 9-10V6L12 2Z"/><path d="m8 12 3 3 5-6"/>'
};
export function icon(name, cls = '') {
  return `<svg class="icon ${cls}" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${shapes[name] || shapes.mountain}</svg>`;
}
