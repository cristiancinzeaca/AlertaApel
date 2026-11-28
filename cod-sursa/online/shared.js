// ===== cod comun: server și pagină =====
const RDISP = ['2','3','4','5','6','7','8','9','10','J','Q','K','A'];
const SUITSYM = ['♠','♥','♦','♣'];
const SUITCLS = ['s','h','d','c'];
const SUITNAME = ['pică','cupă','caro','treflă'];
const STREETS = ['Preflop','Flop','Turn','River'];
function straightTop(mask){
  const m = (mask << 1) | ((mask >> 12) & 1);
  for (let h = 13; h >= 4; h--) if (((m >> (h - 4)) & 31) === 31) return h - 1;
  return -1;
}
function pack(cat, arr){ let s = cat; for (let i = 0; i < 5; i++) s = s * 16 + ((arr[i] ?? -1) + 1); return s; }
function evalHand(cards){
  const cnt = new Array(13).fill(0), sc = [0,0,0,0], sm = [0,0,0,0]; let all = 0;
  for (const c of cards){ const r = c >> 2, s = c & 3; cnt[r]++; sc[s]++; sm[s] |= 1 << r; all |= 1 << r; }
  let fs = -1; for (let s = 0; s < 4; s++) if (sc[s] >= 5) fs = s;
  if (fs >= 0){ const t = straightTop(sm[fs]); if (t >= 0) return pack(8, [t]); }
  let quad = -1; const trips = [], pairs = [];
  for (let r = 12; r >= 0; r--){ if (cnt[r] === 4) quad = r; else if (cnt[r] === 3) trips.push(r); else if (cnt[r] === 2) pairs.push(r); }
  const kick = (excl, n) => { const k = []; for (let r = 12; r >= 0 && k.length < n; r--) if (cnt[r] > 0 && !excl.includes(r)) k.push(r); return k; };
  if (quad >= 0) return pack(7, [quad, ...kick([quad], 1)]);
  if (trips.length && (trips.length > 1 || pairs.length)) return pack(6, [trips[0], Math.max(trips.length > 1 ? trips[1] : -1, pairs.length ? pairs[0] : -1)]);
  if (fs >= 0){ const k = []; for (let r = 12; r >= 0 && k.length < 5; r--) if ((sm[fs] >> r) & 1) k.push(r); return pack(5, k); }
  const st = straightTop(all); if (st >= 0) return pack(4, [st]);
  if (trips.length) return pack(3, [trips[0], ...kick([trips[0]], 2)]);
  if (pairs.length >= 2) return pack(2, [pairs[0], pairs[1], ...kick([pairs[0], pairs[1]], 1)]);
  if (pairs.length === 1) return pack(1, [pairs[0], ...kick([pairs[0]], 3)]);
  return pack(0, kick([], 5));
}
function handName(sc){
  const cat = Math.floor(sc / 1048576); const d = []; let x = sc % 1048576;
  for (let i = 0; i < 5; i++){ d.unshift((x % 16) - 1); x = Math.floor(x / 16); }
  const R = i => RDISP[d[i]];
  switch (cat){
    case 8: return d[0] === 12 ? 'Chintă roială' : `Chintă de culoare până la ${R(0)}`;
    case 7: return `Careu de ${R(0)}`;
    case 6: return `Full house, ${R(0)} peste ${R(1)}`;
    case 5: return `Culoare cu ${R(0)} sus`;
    case 4: return `Chintă până la ${R(0)}`;
    case 3: return `Trei de ${R(0)}`;
    case 2: return `Două perechi, ${R(0)} și ${R(1)}`;
    case 1: return `Pereche de ${R(0)}`;
    default: return `Carte mare ${R(0)}`;
  }
}
function startLabel(h){
  const a = Math.max(h[0] >> 2, h[1] >> 2), b = Math.min(h[0] >> 2, h[1] >> 2);
  if (a === b) return `${RDISP[a]}${RDISP[b]} · pereche`;
  return `${RDISP[a]}${RDISP[b]}${(h[0] & 3) === (h[1] & 3) ? 's · aceeași culoare' : 'o · culori diferite'}`;
}
function equity(hole, board, k, iters){
  k = Math.max(1, k);
  const used = new Set([...hole, ...board]); const rest = [];
  for (let c = 0; c < 52; c++) if (!used.has(c)) rest.push(c);
  const need = 5 - board.length, n = rest.length, draw = 2 * k + need; let score = 0;
  for (let it = 0; it < iters; it++){
    for (let i = 0; i < draw; i++){ const j = i + Math.floor(Math.random() * (n - i)); const t = rest[i]; rest[i] = rest[j]; rest[j] = t; }
    const full = board.concat(rest.slice(2 * k, draw));
    const me = evalHand(hole.concat(full));
    let ties = 0, lost = false;
    for (let o = 0; o < k; o++){ const s = evalHand([rest[2 * o], rest[2 * o + 1]].concat(full)); if (s > me){ lost = true; break; } if (s === me) ties++; }
    if (!lost) score += 1 / (ties + 1);
  }
  return score / iters;
}
function shuffled(){
  const d = [...Array(52).keys()];
  for (let i = 51; i > 0; i--){ const j = Math.floor(Math.random() * (i + 1)); [d[i], d[j]] = [d[j], d[i]]; }
  return d;
}
const cardText = c => `${RDISP[c >> 2]}${SUITSYM[c & 3]}`;
function chen(a, b, suited){
  const hi = Math.max(a, b), lo = Math.min(a, b);
  const val = r => r === 12 ? 10 : r === 11 ? 8 : r === 10 ? 7 : r === 9 ? 6 : (r + 2) / 2;
  if (hi === lo) return Math.max(5, val(hi) * 2);
  let s = val(hi) + (suited ? 2 : 0);
  const gap = hi - lo - 1;
  s -= gap === 0 ? 0 : gap === 1 ? 1 : gap === 2 ? 2 : gap === 3 ? 4 : 5;
  if (gap <= 1 && hi < 10) s += 1;
  return Math.ceil(s);
}
const PCT = new Float32Array(52 * 52);
(() => {
  const list = [];
  for (let i = 0; i < 52; i++) for (let j = i + 1; j < 52; j++){
    const a = i >> 2, b = j >> 2;
    list.push([i, j, chen(a, b, (i & 3) === (j & 3)) * 100 + Math.max(a, b) * 2 + Math.min(a, b) * .1]);
  }
  list.sort((x, y) => y[2] - x[2]);
  list.forEach((c, k) => { const v = (k + .5) / list.length; PCT[c[0] * 52 + c[1]] = v; PCT[c[1] * 52 + c[0]] = v; });
})();
const pctOf = h => PCT[h[0] * 52 + h[1]];
function popcount(x){ let n = 0; while (x){ n += x & 1; x >>= 1; } return n; }
function hasDraw(h, board){
  if (board.length < 3 || board.length >= 5) return false;
  const cs = h.concat(board), sc = [0,0,0,0];
  cs.forEach(c => sc[c & 3]++);
  for (let s = 0; s < 4; s++) if (sc[s] === 4 && ((h[0] & 3) === s || (h[1] & 3) === s)) return true;
  let m = 0, hm = 0;
  cs.forEach(c => { m |= 1 << ((c >> 2) + 1); });
  h.forEach(c => { hm |= 1 << ((c >> 2) + 1); });
  if (m & (1 << 13)) m |= 1; if (hm & (1 << 13)) hm |= 1;
  for (let lo = 0; lo <= 9; lo++){ if (popcount((m >> lo) & 31) === 4 && ((hm >> lo) & 31)) return true; }
  return false;
}
const STYLES = {
  prudent:    {label:'Prudent',    pfRaise:.58, raise:.78, bet:.62, bluff:.05, margin:.06,  respect:.12, slow:.10},
  echilibrat: {label:'Echilibrat', pfRaise:.54, raise:.72, bet:.56, bluff:.14, margin:0,    respect:.08, slow:.15},
  agresiv:    {label:'Agresiv',    pfRaise:.50, raise:.64, bet:.50, bluff:.28, margin:-.05, respect:.04, slow:.05}
};
const LEVELS = [[10,20,0],[15,30,0],[25,50,5],[50,100,10],[75,150,15],[100,200,25],[150,300,40],[200,400,50],[300,600,75],[400,800,100],[600,1200,150],[1000,2000,250],[1500,3000,400],[2500,5000,600]];
const HANDS_PER_LEVEL = 8, TSTART = 1500;
const payouts = n => n >= 6 ? [50, 30, 20] : n >= 4 ? [65, 35] : [100];
function icm(stacks, pay){
  const res = stacks.map(() => 0);
  const rec = (left, place, prob) => {
    if (place >= pay.length || !left.length) return;
    const tot = left.reduce((s, i) => s + stacks[i], 0);
    for (const i of left){ const p = prob * stacks[i] / tot; res[i] += p * pay[place]; rec(left.filter(j => j !== i), place + 1, p); }
  };
  rec(stacks.map((s, i) => i).filter(i => stacks[i] > 0), 0, 1);
  return res;
}
function pushPct(eff, behind){
  const T = [[2,.85],[4,.68],[6,.52],[8,.42],[10,.34],[12,.28],[15,.22]];
  let v = T[T.length - 1][1];
  for (let k = 0; k < T.length; k++){
    if (eff <= T[k][0]){ if (k === 0){ v = T[0][1]; break; } const [e0, v0] = T[k - 1], [e1, v1] = T[k]; v = v0 + (v1 - v0) * (eff - e0) / (e1 - e0); break; }
  }
  return Math.max(.04, v * Math.pow(.8, Math.max(0, behind - 1)));
}
// ===== sfârșitul codului comun =====
