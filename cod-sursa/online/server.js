// ===== server de poker: mese, reguli, boți =====
const PORT = Number(Bun.env.PORT || 3000);
// Pagina jocului stă în serviciul „poker-web” (web.js). Serverul o descarcă de acolo și o servește la aceeași adresă,
// ca link-urile vechi și conexiunea WebSocket să rămână neschimbate.
const WEB_URL = Bun.env.WEB_URL || 'https://poker-web-production-fe6e.up.railway.app/';
let CLIENT = null;
async function loadClient(){
  try { const r = await fetch(WEB_URL, { headers:{ 'Cache-Control':'no-cache' } }); if (r.ok) CLIENT = Bun.gzipSync(new Uint8Array(await r.arrayBuffer())); } catch (e) {}
}
await loadClient();
setInterval(loadClient, 5 * 60 * 1000);
const BOT_NAMES = ['Vlad','Ioana','Radu','Elena','Mihai','Sorin'];
const MIX = ['echilibrat','agresiv','prudent','agresiv','echilibrat','prudent'];
const BLINDS = [[5,10],[10,20],[25,50],[50,100]];
const tables = new Map();
const newStats = () => ({ hands:0, vpip:0, pfr:0, agg:0, calls:0, faced:0, foldFaced:0 });
const handBlank = () => ({ hole:[], bet:0, com:0, folded:true, allin:false, acted:false, last:'', inHand:false, aggStreet:-1,
  hx:{ pfAct:'none', postAgg:0, postCall:0, pfRaises:0, vpip:false, pfr:false } });
const clean = (s, n) => String(s || '').replace(/[<>&"'`]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
function send(ws, o){ try { ws.send(JSON.stringify(o)); } catch (e) {} }

class Table {
  constructor(){
    const A = 'ABCDEFGHJKLMNPQRSTUVWXYZ'; let c;
    do { c = ''; for (let i = 0; i < 4; i++) c += A[Math.floor(Math.random() * A.length)]; } while (tables.has(c));
    this.code = c; this.seats = Array(6).fill(null); this.hostId = null;
    this.settings = { blinds:0, stack:1000, coachAllowed:true, rebuy:true, botStyle:'mix', turnTime:30, mode:'cash' };
    this.running = false; this.waiting = false; this.handNum = 0; this.button = -1; this.tour = null;
    this.log = []; this.chat = []; this.h = null; this.tm = {}; this.idle = Date.now();
    tables.set(c, this);
  }
  blindsNow(){ return this.tour ? LEVELS[this.tour.level] : [...BLINDS[this.settings.blinds], 0]; }
  get sb(){ return this.blindsNow()[0]; }
  get bb(){ return this.blindsNow()[1]; }
  startTour(){
    const ps = this.seats.filter(Boolean);
    ps.forEach(p => { p.stack = TSTART; p.out = false; p.place = 0; p.buyins = 1; });
    this.tour = { level:0, handsAtLevel:0, startN:ps.length, over:false };
    this.L(`Turneu nou: ${ps.length} jucători, ${TSTART} jetoane fiecare, blindurile cresc la fiecare ${HANDS_PER_LEVEL} mâini.`, 'st');
  }
  L(text, cls){ this.log.push({ text, cls: cls || '' }); if (this.log.length > 150) this.log.splice(0, this.log.length - 150); }
  clear(){ for (const k in this.tm) clearTimeout(this.tm[k]); this.tm = {}; }
  nextIdx(i, pred){ for (let k = 1; k <= 6; k++){ const j = (i + k + 6) % 6; if (this.seats[j] && pred(j)) return j; } return -1; }
  alive(j){ const p = this.seats[j]; return !!p && p.inHand && !p.folded; }
  canAct(j){ return this.alive(j) && !this.seats[j].allin; }
  needsAct(j){ const p = this.seats[j]; return this.canAct(j) && (!p.acted || p.bet < this.h.currentBet); }
  pot(){ return this.seats.reduce((s, p) => s + (p ? p.com : 0), 0); }
  post(i, a){ const p = this.seats[i]; a = Math.max(0, Math.min(a, p.stack)); p.stack -= a; p.bet += a; p.com += a; if (p.stack === 0) p.allin = true; return a; }
  legal(i){
    const p = this.seats[i], h = this.h, toCall = Math.max(0, h.currentBet - p.bet), maxTo = p.bet + p.stack;
    const others = this.seats.some((q, j) => q && j !== i && this.canAct(j));
    return { toCall, callAmt: Math.min(toCall, p.stack), canCheck: toCall === 0, canRaise: p.stack > toCall && others,
      maxTo, minTo: Math.min(h.currentBet + h.minRaise, maxTo) };
  }
  seatIdx(id){ return this.seats.findIndex(p => p && p.id === id); }
  humansOnline(){ return this.seats.some(p => p && !p.bot && p.connected); }
  kick(){ if (this.running && (!this.h || this.h.over) && !this.tm.next) this.tm.next = setTimeout(() => this.startHand(), 1500); }

  /* ---- jucători ---- */
  join(ws, name, token){
    let i = token ? this.seats.findIndex(p => p && !p.bot && p.id === token) : -1;
    if (i < 0){
      i = this.seats.findIndex(p => !p);
      if (i < 0) return send(ws, { t:'error', msg:'Masa e plină: toate cele 6 locuri sunt ocupate.' });
      const late = this.tour && !this.tour.over;
      this.seats[i] = { id: crypto.randomUUID(), name: clean(name, 16) || 'Jucător', bot:false, stack: late ? 0 : this.settings.stack, buyins:1,
        st:newStats(), coach:false, sitOut:false, ws:null, connected:false, leaving:false, out:late, place:0, jv:new Set(), jtok:null, ...handBlank() };
      this.L(`${this.seats[i].name} s-a așezat la masă${late ? ' și intră de la turneul următor' : ''}.`);
    } else this.L(`${this.seats[i].name} s-a reconectat.`);
    const p = this.seats[i];
    if (p.ws && p.ws !== ws){ try { p.ws.data.table = null; p.ws.close(); } catch (e) {} }
    p.ws = ws; p.connected = true; ws.data.table = this.code; ws.data.id = p.id;
    if (!this.seats.some(q => q && q.id === this.hostId && q.connected)) this.hostId = p.id;
    send(ws, { t:'joined', code:this.code, token:p.id });
    if (this.h && !this.h.over && !this.h.runout && this.h.toAct === i) this.armTurn();
    this.kick(); this.push();
  }
  disconnect(id, ws){
    const i = this.seatIdx(id); if (i < 0) return;
    const p = this.seats[i]; if (p.ws !== ws) return;
    p.ws = null; p.connected = false; this.L(`${p.name} s-a deconectat.`);
    this.fixHost();
    if (this.h && !this.h.over && !this.h.runout && this.h.toAct === i) this.armTurn();
    this.push();
  }
  fixHost(){
    if (this.seats.some(q => q && q.id === this.hostId && q.connected)) return;
    const n = this.seats.find(q => q && !q.bot && q.connected);
    if (n){ this.hostId = n.id; this.L(`${n.name} e acum gazda mesei.`); }
  }
  addBot(){
    const i = this.seats.findIndex(p => !p); if (i < 0 || (this.tour && !this.tour.over)) return;
    const used = new Set(this.seats.filter(Boolean).map(p => p.name));
    const name = BOT_NAMES.find(n => !used.has(n)) || 'Bot';
    const style = this.settings.botStyle === 'mix' ? MIX[i] : this.settings.botStyle;
    this.seats[i] = { id: crypto.randomUUID(), name, bot:true, style, stack:this.settings.stack, buyins:1, st:newStats(),
      coach:false, sitOut:false, connected:true, leaving:false, ...handBlank() };
    this.L(`Botul ${name} (${STYLES[style].label.toLowerCase()}) s-a așezat.`);
    this.kick();
  }
  removeSeat(i){
    const p = this.seats[i]; if (!p) return;
    if (this.h && !this.h.over && p.inHand){ p.leaving = true; if (this.alive(i)) this.foldOut(i); }
    else this.seats[i] = null;
  }
  foldOut(i){
    const h = this.h, p = this.seats[i];
    if (h.toAct === i && !h.runout) return this.act(i, 'fold');
    p.folded = true; p.last = 'Fold'; this.L(`${p.name}: fold`);
    const left = this.seats.map((_, j) => j).filter(j => this.alive(j));
    if (left.length === 1 && !h.runout) this.winByFold(left[0]);
  }
  positions(n){
    const lab = {}, inH = j => this.seats[j].inHand;
    if (n === 2){ lab[this.button] = 'BTN/SB'; lab[this.nextIdx(this.button, inH)] = 'BB'; return lab; }
    const names = { 3:['BTN','SB','BB'], 4:['BTN','SB','BB','UTG'], 5:['BTN','SB','BB','UTG','CO'], 6:['BTN','SB','BB','UTG','HJ','CO'] }[n];
    let k = this.button; for (let t = 0; t < n; t++){ lab[k] = names[t]; k = this.nextIdx(k, inH); }
    return lab;
  }

  /* ---- desfășurarea mâinii ---- */
  startHand(){
    this.clear();
    if (!this.running) return this.push();
    this.seats.forEach((p, i) => { if (p && p.leaving) this.seats[i] = null; });
    const T = this.tour;
    if (!T) this.seats.forEach(p => { if (p && p.bot && p.stack <= 0){ p.stack = this.settings.stack; p.buyins++; this.L(`${p.name} se reîncarcă cu ${p.stack}.`); } });
    else {
      if (T.over){ this.running = false; return this.push(); }
      if (T.handsAtLevel >= HANDS_PER_LEVEL && T.level < LEVELS.length - 1){
        T.level++; T.handsAtLevel = 0;
        const [s, b, a] = LEVELS[T.level];
        this.L(`Nivelul ${T.level + 1}: blinduri ${s}/${b}${a ? `, ante ${a}` : ''}.`, 'st');
      }
    }
    const can = p => p && p.stack > 0 && !p.out && (T ? true : !p.sitOut) && (p.bot || p.connected);
    this.seats.forEach(p => { if (p){ Object.assign(p, handBlank()); p.inHand = can(p); p.folded = !p.inHand; p.handStart = p.stack; } });
    const n = this.seats.filter(p => p && p.inHand).length;
    if (n < 2 || !this.humansOnline()){ this.h = null; this.waiting = true; return this.push(); }
    this.waiting = false;
    if (T) T.handsAtLevel++;
    const [sb0, bb0, ante] = this.blindsNow();
    const h = this.h = { street:0, board:[], deck:shuffled(), currentBet:bb0, minRaise:bb0, toAct:-1, over:false, runout:false, reveal:false,
      lastAgg:-1, raises:0, pfAgg:-1, pos:{}, result:null, winners:[], deadline:0, nextAt:0, bb:bb0, ante };
    if (ante) this.seats.forEach(p => { if (p && p.inHand){ const a = Math.min(ante, p.stack); p.stack -= a; p.com += a; if (p.stack === 0) p.allin = true; } });
    this.handNum++;
    this.seats.forEach(p => { if (p && p.inHand) p.st.hands++; });
    const inH = j => this.seats[j].inHand;
    this.button = this.nextIdx(this.button < 0 ? 5 : this.button, inH);
    const sb = n === 2 ? this.button : this.nextIdx(this.button, inH), bb = this.nextIdx(sb, inH);
    for (let r = 0; r < 2; r++){ let k = sb; for (let t = 0; t < n; t++){ this.seats[k].hole.push(h.deck.pop()); k = this.nextIdx(k, inH); } }
    this.post(sb, sb0); this.post(bb, bb0);
    this.seats[sb].last = `SB ${this.seats[sb].bet}`; this.seats[bb].last = `BB ${this.seats[bb].bet}`;
    h.pos = this.positions(n);
    this.L(`Mâna #${this.handNum} · ${n} jucători · buton: ${this.seats[this.button].name} · blinduri ${sb0}/${bb0}${ante ? ` ante ${ante}` : ''}`, 'hd');
    const f = this.nextIdx(bb, j => this.needsAct(j));
    if (f < 0) return this.endRound();
    h.toAct = f; this.turn();
  }
  act(i, type, to){
    const h = this.h, p = this.seats[i], L = this.legal(i);
    clearTimeout(this.tm.turn); clearTimeout(this.tm.bot);
    if (type === 'raise' && !L.canRaise) type = L.toCall ? 'call' : 'check';
    if (type === 'check' && !L.canCheck) type = 'call';
    const hx = p.hx, st = p.st;
    if (h.street === 0){
      if (type === 'raise'){ if (!hx.vpip){ hx.vpip = true; st.vpip++; } if (!hx.pfr){ hx.pfr = true; st.pfr++; } hx.pfAct = 'raise'; hx.pfRaises++; }
      else if (type === 'call' && L.toCall > 0){ if (!hx.vpip){ hx.vpip = true; st.vpip++; } if (hx.pfAct !== 'raise') hx.pfAct = h.currentBet > h.bb ? 'call' : 'limp'; }
    } else {
      if (L.toCall > 0){ st.faced++; if (type === 'fold') st.foldFaced++; }
      if (type === 'raise'){ st.agg++; hx.postAgg++; } else if (type === 'call' && L.toCall > 0){ st.calls++; hx.postCall++; }
    }
    if (type === 'raise'){ h.lastAgg = i; h.raises++; p.aggStreet = h.street; }
    if (type === 'fold'){
      p.folded = true; p.last = 'Fold'; p.acted = true; this.L(`${p.name}: fold`);
      const left = this.seats.map((_, j) => j).filter(j => this.alive(j));
      if (left.length === 1) return this.winByFold(left[0]);
      return this.proceed(i);
    }
    if (type === 'check' || (type === 'call' && L.toCall === 0)){ p.last = 'Check'; this.L(`${p.name}: check`); }
    else if (type === 'call'){
      this.post(i, L.callAmt);
      p.last = p.allin ? `All-in ${p.bet}` : `Call ${L.callAmt}`;
      this.L(`${p.name}: call ${L.callAmt}${p.allin ? ' (all-in)' : ''}`);
    } else {
      let t = Math.round(Number(to) || L.minTo); t = Math.max(L.minTo, Math.min(L.maxTo, t));
      const isBet = h.currentBet === 0;
      this.post(i, t - p.bet);
      const inc = t - h.currentBet; if (inc >= h.minRaise) h.minRaise = inc;
      h.currentBet = Math.max(h.currentBet, t);
      this.seats.forEach((q, j) => { if (q && j !== i) q.acted = false; });
      p.last = p.allin ? `All-in ${t}` : (isBet ? `Bet ${t}` : `Raise la ${t}`);
      this.L(`${p.name}: ${p.allin ? 'all-in ' + t : (isBet ? 'bet ' + t : 'raise la ' + t)}`);
    }
    p.acted = true;
    this.proceed(i);
  }
  proceed(i){
    const n = this.nextIdx(i, j => this.needsAct(j));
    if (n < 0) return this.endRound();
    this.h.toAct = n; this.turn();
  }
  endRound(){
    const h = this.h;
    let top = -1, topCom = -1, second = 0;
    this.seats.forEach((p, j) => { if (!p || !p.inHand) return; if (p.com > topCom){ second = Math.max(second, topCom); topCom = p.com; top = j; } else second = Math.max(second, p.com); });
    if (top >= 0 && topCom > second && !this.seats[top].folded){
      const d = topCom - second, p = this.seats[top];
      p.stack += d; p.com -= d; p.bet = Math.max(0, p.bet - d); if (p.stack > 0) p.allin = false;
      this.L(`${p.name} primește înapoi ${d} (neplătit)`);
    }
    this.seats.forEach(p => { if (p) p.bet = 0; });
    if (h.street === 0) h.pfAgg = h.lastAgg;
    h.currentBet = 0; h.minRaise = h.bb; h.lastAgg = -1; h.raises = 0;
    if (h.street === 3) return this.showdown();
    const live = this.seats.map((_, j) => j).filter(j => this.alive(j));
    if (live.filter(j => this.canAct(j)).length <= 1){
      h.reveal = true; h.runout = true; h.toAct = -1;
      this.L(`All-in. Cărțile pe masă: ${live.map(j => `${this.seats[j].name} ${this.seats[j].hole.map(cardText).join(' ')}`).join(' · ')}`);
      this.push(); this.tm.run = setTimeout(() => this.runout(), 1200); return;
    }
    this.dealStreet();
    this.seats.forEach(p => { if (p && p.inHand && !p.folded){ p.acted = false; if (!p.allin) p.last = ''; } });
    h.toAct = this.nextIdx(this.button, j => this.canAct(j));
    this.turn();
  }
  dealStreet(){
    const h = this.h; h.street++;
    const n = h.street === 1 ? 3 : 1, cards = [];
    h.deck.pop();
    for (let i = 0; i < n; i++){ const c = h.deck.pop(); h.board.push(c); cards.push(c); }
    this.L(`${STREETS[h.street]}: ${cards.map(cardText).join(' ')} · pot ${this.pot()}`, 'st');
  }
  runout(){
    if (this.h.street >= 3) return this.showdown();
    this.dealStreet(); this.push(); this.tm.run = setTimeout(() => this.runout(), 1200);
  }
  showdown(){
    const h = this.h; h.reveal = true; h.runout = false;
    const cont = this.seats.map((_, j) => j).filter(j => this.alive(j));
    const sc = {}, nm = {};
    cont.forEach(j => { sc[j] = evalHand(this.seats[j].hole.concat(h.board)); nm[j] = handName(sc[j]); });
    this.L(`Showdown: ${cont.map(j => `${this.seats[j].name} ${this.seats[j].hole.map(cardText).join(' ')} (${nm[j]})`).join(' · ')}`);
    const order = []; { let k = this.button; for (let t = 0; t < 6; t++){ k = (k + 1) % 6; order.push(k); } }
    const levels = [...new Set(cont.map(j => this.seats[j].com))].sort((a, b) => a - b);
    const won = Array(6).fill(0), pots = []; let prev = 0;
    levels.forEach((lv, idx) => {
      let slice = 0;
      this.seats.forEach(p => { if (p && p.inHand) slice += Math.min(p.com, lv) - Math.min(p.com, prev); });
      if (idx === levels.length - 1){ const counted = this.seats.reduce((s, p) => s + (p && p.inHand ? Math.min(p.com, lv) : 0), 0); slice += this.pot() - counted; }
      const elig = cont.filter(j => this.seats[j].com >= lv);
      if (slice > 0){
        const best = Math.max(...elig.map(j => sc[j]));
        const ws = order.filter(j => elig.includes(j) && sc[j] === best);
        const share = Math.floor(slice / ws.length); let odd = slice - share * ws.length;
        ws.forEach(j => { won[j] += share; });
        for (let k = 0; odd > 0; k++, odd--) won[ws[k % ws.length]]++;
        pots.push({ amt:slice, ws });
      }
      prev = lv;
    });
    won.forEach((a, j) => { if (this.seats[j]) this.seats[j].stack += a; });
    pots.forEach((pt, k) => {
      const label = pots.length === 1 ? 'Potul' : (k === 0 ? 'Potul principal' : `Pot secundar ${k}`);
      this.L(`${label} (${pt.amt}): ${pt.ws.map(j => this.seats[j].name).join(' și ')}${pt.ws.length > 1 ? ' împart' : ''} cu ${nm[pt.ws[0]]}`, 'win');
    });
    const main = pots[0];
    h.winners = won.map((a, j) => a > 0 ? j : -1).filter(j => j >= 0);
    const title = main.ws.length > 1 ? `${main.ws.map(j => this.seats[j].name).join(' și ')} împart potul` : `${this.seats[main.ws[0]].name} câștigă ${won[main.ws[0]]}`;
    const detail = cont.map(j => `${this.seats[j].name}: ${nm[j]}`).join(' · ') + (pots.length > 1 ? ` · ${pots.length} poturi` : '');
    this.finish(title, detail, won, false);
  }
  winByFold(w){
    const amt = this.pot(); this.seats[w].stack += amt; this.h.winners = [w];
    const won = Array(6).fill(0); won[w] = amt;
    this.L(`${this.seats[w].name} ia potul de ${amt}.`, 'win');
    this.finish(`${this.seats[w].name} ia potul de ${amt}`, 'Toți ceilalți au dat fold.', won, true);
  }
  finish(title, detail, won, byFold){
    const h = this.h; h.over = true; h.runout = false; h.toAct = -1; h.deadline = 0;
    clearTimeout(this.tm.bot); clearTimeout(this.tm.turn); clearTimeout(this.tm.run);
    const T = this.tour;
    if (!T){
      const broke = this.seats.filter(p => p && p.stack === 0).map(p => p.name);
      if (broke.length) detail += ` · Fără jetoane: ${broke.join(', ')}.`;
    } else {
      const out = this.seats.filter(p => p && !p.out && p.inHand && p.stack === 0).sort((a, b) => a.handStart - b.handStart);
      let left = this.seats.filter(p => p && !p.out).length;
      out.forEach(p => { p.out = true; p.place = left--; this.L(`${p.name} e eliminat pe locul ${p.place}.`, 'st'); });
      if (out.length) detail += ` · Eliminați: ${out.map(p => `${p.name} (locul ${p.place})`).join(', ')}.`;
      const still = this.seats.filter(p => p && !p.out);
      if (still.length <= 1){
        if (still[0]){ still[0].place = 1; still[0].out = true; }
        T.over = true; this.running = false;
        const pay = payouts(T.startN);
        const ranked = this.seats.filter(p => p && p.place > 0).sort((a, b) => a.place - b.place);
        title = `${ranked[0] ? ranked[0].name : '?'} câștigă turneul!`;
        detail = 'Clasament: ' + ranked.map(p => `${p.place}. ${p.name}${p.place <= pay.length ? ` (${pay[p.place - 1]}%)` : ''}`).join(' · ');
        this.L(title, 'win');
      }
    }
    h.result = { title, detail, won, byFold: !!byFold, tourOver: !!(T && T.over) };
    if (this.running){ h.nextAt = Date.now() + 6000; this.tm.next = setTimeout(() => this.startHand(), 6000); }
    this.push();
  }
  turn(){
    const h = this.h; if (!h || h.over) return;
    const i = h.toAct, p = this.seats[i];
    clearTimeout(this.tm.bot); clearTimeout(this.tm.turn);
    if (p.bot){
      h.deadline = 0;
      this.tm.bot = setTimeout(() => { if (this.h !== h || h.over || h.toAct !== i) return; const d = this.botDecide(i); this.act(i, d.type, d.to); }, 700 + Math.random() * 1000);
    } else this.armTurn();
    this.push();
  }
  armTurn(){
    const h = this.h, i = h.toAct, p = this.seats[i];
    clearTimeout(this.tm.turn);
    const secs = p.connected && !p.leaving ? this.settings.turnTime : 6;
    h.deadline = Date.now() + secs * 1000;
    this.tm.turn = setTimeout(() => {
      if (this.h !== h || h.over || h.toAct !== i) return;
      const L = this.legal(i); this.L(`${p.name}: timpul a expirat.`);
      this.act(i, L.canCheck ? 'check' : 'fold');
    }, secs * 1000);
  }

  /* ---- boții ---- */
  botDecide(i){
    const h = this.h, p = this.seats[i], st = STYLES[p.style] || STYLES.echilibrat, L = this.legal(i), pre = h.street === 0, BB = h.bb;
    const opps = this.seats.filter((q, j) => q && j !== i && this.alive(j)).length;
    const eq = equity(p.hole, h.board, opps, pre ? 300 : 450);
    const fair = 1 / (opps + 1), eqH = opps <= 1 ? eq : .5 + (eq - fair) * .5 / (1 - fair);
    const pt = this.pot(), r = Math.random();
    const behind = this.seats.filter((q, j) => q && j !== i && this.canAct(j) && !q.acted).length;
    const size = (bluff) => {
      let t;
      if (pre){
        if (h.currentBet <= BB){ const limpers = this.seats.filter((q, j) => q && j !== i && this.alive(j) && q.acted && q.bet >= BB).length; t = BB * (2.5 + Math.random() * .5) + BB * limpers; }
        else t = h.currentBet * (2.7 + Math.random() * .6);
      } else if (h.currentBet === 0) t = pt * [.5, .66, .75, 1][Math.floor(Math.random() * 4)];
      else t = h.currentBet + (pt + L.toCall) * (.6 + Math.random() * .3);
      if (!bluff && !pre && ((eqH > .9 && Math.random() < .3) || (p.stack < pt * 1.3 && eqH > st.bet))) t = L.maxTo;
      t = Math.round(t / 5) * 5; t = Math.max(L.minTo, Math.min(L.maxTo, t)); if (t >= L.maxTo * .8) t = L.maxTo;
      return { type:'raise', to:t };
    };
    const potOdds = L.callAmt / (pt + L.callAmt), betFrac = L.toCall / Math.max(BB, pt - L.toCall);
    const effBB = Math.min(p.stack + p.bet, Math.max(0, ...this.seats.filter((q, j) => q && j !== i && this.alive(j)).map(q => q.stack + q.bet))) / BB;
    if (pre && effBB <= 10){
      const pp = pushPct(effBB, behind) * (p.style === 'agresiv' ? 1.25 : p.style === 'prudent' ? .75 : 1), mp = pctOf(p.hole);
      if (h.currentBet <= BB){
        if (L.toCall === 0) return (L.canRaise && mp <= pp) ? { type:'raise', to:L.maxTo } : { type:'check' };
        if (L.canRaise && mp <= pp) return { type:'raise', to:L.maxTo };
        return (L.toCall < BB && mp <= pp * 1.4) ? { type:'call' } : { type:'fold' };
      }
      if (eq - st.respect * .5 >= potOdds + st.margin + .02) return (L.canRaise && mp <= pp * .4) ? { type:'raise', to:L.maxTo } : { type:'call' };
      return { type:'fold' };
    }
    if (pre){
      const openT = st.pfRaise + .005 * behind;
      if (L.toCall === 0) return (L.canRaise && eqH >= openT && r < .8) ? size(false) : { type:'check' };
      if (h.currentBet <= BB){
        if (L.canRaise && eqH >= openT) return size(false);
        if (eqH >= openT - (L.toCall < BB ? .12 : .07) && r < .75) return { type:'call' };
        if (L.canRaise && behind <= 2 && r < st.bluff * .5) return size(true);
        return { type:'fold' };
      }
      if (L.canRaise && eqH >= openT + .15 && r < .8) return size(false);
      if (eq - st.respect * Math.min(betFrac, 2) * .6 >= potOdds + st.margin) return { type:'call' };
      if (L.canRaise && opps <= 2 && r < st.bluff * .25) return size(true);
      return { type:'fold' };
    }
    if (L.toCall === 0){
      if (!L.canRaise) return { type:'check' };
      if (eqH >= st.raise) return r < st.slow ? { type:'check' } : size(false);
      if (eqH >= st.bet) return r < .7 ? size(false) : { type:'check' };
      if (r < st.bluff / opps) return size(true);
      return { type:'check' };
    }
    if (L.canRaise && eqH >= st.raise && r < .7) return size(false);
    if (eq - st.respect * Math.min(betFrac, 2) >= potOdds + st.margin) return { type:'call' };
    if (L.canRaise && r < st.bluff * .4 / opps && L.toCall <= pt * .7) return size(true);
    return { type:'fold' };
  }

  /* ---- trimiterea stării ---- */
  stateFor(v){
    const h = this.h, me = this.seats[v];
    const show = (p, i) => i === v || (h && h.reveal && p.inHand && !p.folded);
    const [csb, cbb, cante] = this.blindsNow();
    return { t:'state', code:this.code, you:v, host: !!me && me.id === this.hostId, settings:this.settings, sb:csb, bb:cbb, ante:cante,
      running:this.running, waiting:this.waiting, handNum:this.handNum, button:this.button, now:Date.now(),
      tour: this.tour && { ...this.tour, hpl:HANDS_PER_LEVEL },
      h: h && { street:h.street, board:h.board, currentBet:h.currentBet, minRaise:h.minRaise, toAct:h.toAct, over:h.over, runout:h.runout,
        lastAgg:h.lastAgg, raises:h.raises, pfAgg:h.pfAgg, pos:h.pos, result:h.result, winners:h.winners, deadline:h.deadline, nextAt:h.nextAt, bb:h.bb, ante:h.ante },
      seats: this.seats.map((p, i) => p && { name:p.name, bot:p.bot, style:p.style || null, stack:p.stack, buyins:p.buyins, bet:p.bet, com:p.com,
        out:!!p.out, place:p.place || 0,
        folded:p.folded, allin:p.allin, inHand:p.inHand, last:p.last, connected:p.connected, coach:p.coach, sitOut:p.sitOut, st:p.st, hx:p.hx,
        aggStreet:p.aggStreet, host: p.id === this.hostId, jarvis: !!(p.jv && p.jv.size),
        hole: show(p, i) ? p.hole : (h && !h.over && p.inHand && !p.folded ? [-1, -1] : []) }),
      legal: h && !h.over && !h.runout && h.toAct === v ? this.legal(v) : null,
      log: this.log.slice(-80), chat: this.chat.slice(-40) };
  }
  push(){
    let any = false;
    this.seats.forEach((p, i) => {
      if (!p || p.bot) return;
      if (p.ws){ any = true; send(p.ws, this.stateFor(i)); }
      if (p.jv && p.jv.size){ const s = JSON.stringify({ ...this.stateFor(i), jarvis:true }); p.jv.forEach(w => { try { w.send(s); } catch (e) {} }); }
    });
    if (any) this.idle = Date.now();
  }
  dropJarvis(p, why){
    if (!p || !p.jv) return;
    p.jv.forEach(w => { send(w, { t:'jarvisOff', msg:why }); w.data.table = null; try { w.close(); } catch (e) {} });
    p.jv.clear(); p.jtok = null;
  }
}

function handle(ws, raw){
  let m;
  try { if (typeof raw !== 'string' || raw.length > 2000) return; m = JSON.parse(raw); } catch (e) { return; }
  if (m.t === 'create'){ const t = new Table(); t.L(`Masa ${t.code} a fost creată.`, 'st'); t.join(ws, m.name, null); return; }
  if (m.t === 'join'){
    const t = tables.get(String(m.code || '').toUpperCase().trim());
    if (!t) return send(ws, { t:'error', code:'nf', msg:'Nu există nicio masă cu codul ăsta. Verifică literele sau creează o masă nouă.' });
    return t.join(ws, m.name, m.token);
  }
  if (m.t === 'jarvis'){
    // Jarvis se leagă de locul unui jucător: prima dată cu codul de 6 cifre, apoi cu jetonul primit
    const t = tables.get(String(m.code || '').toUpperCase().trim());
    const bad = msg => send(ws, { t:'error', code:'jv', msg });
    if (!t) return bad('Nu există nicio masă cu codul ăsta.');
    if (!t.settings.coachAllowed) return bad('Gazda nu permite antrenori la această masă.');
    let i = -1;
    if (m.jtok) i = t.seats.findIndex(p => p && p.jtok && p.jtok === m.jtok);
    else { const e = t.jcodes && t.jcodes[String(m.pin || '')]; if (e && e.exp > Date.now()){ i = t.seatIdx(e.id); delete t.jcodes[String(m.pin)]; } }
    if (i < 0) return bad(m.jtok ? 'Legătura cu masa a expirat. Cere un cod Jarvis nou din joc.' : 'Codul Jarvis nu e bun sau a expirat (expiră după 5 minute). Cere unul nou din joc.');
    const p = t.seats[i];
    if (!p.jtok) p.jtok = crypto.randomUUID();
    const fresh = !p.jv.size;
    p.jv.add(ws); ws.data.table = t.code; ws.data.id = p.id; ws.data.jarvis = true;
    send(ws, { t:'jarvisOk', code:t.code, name:p.name, jtok:p.jtok });
    if (fresh) t.L(`${p.name} și-a conectat Jarvis.`, 'st');
    t.push(); return;
  }
  if (ws.data.jarvis) return; // Jarvis doar citește: nu poate juca
  const t = tables.get(ws.data.table); if (!t) return;
  const i = t.seatIdx(ws.data.id); if (i < 0) return;
  const p = t.seats[i], host = p.id === t.hostId;
  switch (m.t){
    case 'act':
      if (t.h && !t.h.over && !t.h.runout && t.h.toAct === i && ['fold','check','call','raise'].includes(m.type)) t.act(i, m.type, Number(m.to));
      break;
    case 'start':
      if (host && !t.running){
        if (t.settings.mode === 'turneu' && (!t.tour || t.tour.over)){
          if (t.seats.filter(Boolean).length < 2){ send(ws, { t:'note', msg:'Pentru un turneu îți trebuie cel puțin doi jucători sau boți.' }); break; }
          t.startTour();
        }
        t.running = true; t.L('Jocul a început.', 'st'); if (!t.h || t.h.over) t.startHand(); else t.push();
      }
      break;
    case 'pause':
      if (host && t.running){ t.running = false; clearTimeout(t.tm.next); t.tm.next = null; t.L('Gazda a pus pauză. Mâna în curs se termină normal.', 'st'); if (t.h && t.h.over) t.h.nextAt = 0; t.push(); }
      break;
    case 'addBot': if (host){ t.addBot(); t.push(); } break;
    case 'remove':
      if (host){
        const j = Number(m.seat), q = t.seats[j];
        if (q && j !== i){
          t.L(`${q.name} a fost scos de la masă.`);
          if (!q.bot && q.ws){ send(q.ws, { t:'kicked' }); q.ws.data.table = null; q.ws = null; q.connected = false; }
          if (!q.bot) t.dropJarvis(q, 'Gazda te-a scos de la masă.');
          t.removeSeat(j); t.push();
        }
      }
      break;
    case 'settings':
      if (host){
        const s = m.s || {}, st = t.settings;
        if ([0,1,2,3].includes(s.blinds)) st.blinds = s.blinds;
        if ([500,1000,2000,5000].includes(s.stack)) st.stack = s.stack;
        if ([15,30,60].includes(s.turnTime)) st.turnTime = s.turnTime;
        if (['mix','prudent','echilibrat','agresiv'].includes(s.botStyle)){ st.botStyle = s.botStyle; t.seats.forEach((q, j) => { if (q && q.bot) q.style = s.botStyle === 'mix' ? MIX[j] : s.botStyle; }); }
        if (typeof s.coachAllowed === 'boolean'){ st.coachAllowed = s.coachAllowed; if (!s.coachAllowed) t.seats.forEach(q => { if (q){ q.coach = false; t.dropJarvis(q, 'Gazda a oprit antrenorii la această masă.'); } }); }
        if (typeof s.rebuy === 'boolean') st.rebuy = s.rebuy;
        if (['cash','turneu'].includes(s.mode) && s.mode !== st.mode && !t.running){
          st.mode = s.mode;
          if (s.mode === 'cash'){ t.tour = null; t.seats.forEach(q => { if (q){ q.out = false; q.place = 0; if (q.stack <= 0 && q.bot) q.stack = st.stack; } }); }
        }
        t.L('Gazda a schimbat setările mesei.'); t.push();
      }
      break;
    case 'coach': {
      const on = !!m.on && t.settings.coachAllowed;
      if (on !== p.coach){ p.coach = on; t.L(`${p.name} ${on ? 'și-a pornit' : 'și-a oprit'} antrenorul.`, 'st'); t.push(); }
      break;
    }
    case 'jarvisCode': {
      if (!t.settings.coachAllowed){ send(ws, { t:'note', msg:'Gazda nu permite antrenori la această masă.' }); break; }
      t.jcodes = t.jcodes || {};
      let pin; do { pin = String(100000 + Math.floor(Math.random() * 900000)); } while (t.jcodes[pin]);
      t.jcodes[pin] = { id:p.id, exp: Date.now() + 5 * 60 * 1000 };
      send(ws, { t:'jarvisCode', pin, code:t.code });
      break;
    }
    case 'jarvisOff':
      if (p.jv && p.jv.size){ t.dropJarvis(p, 'Te-ai deconectat din joc.'); t.L(`${p.name} și-a deconectat Jarvis.`, 'st'); t.push(); }
      break;
    case 'sitout':
      if (!!m.on !== p.sitOut){ p.sitOut = !!m.on; t.L(`${p.name} ${p.sitOut ? 'ia o pauză' : 'revine la masă'}.`); if (!p.sitOut) t.kick(); t.push(); }
      break;
    case 'rebuy':
      if (p.stack <= 0 && t.settings.rebuy && !t.tour && !(t.h && !t.h.over && p.inHand)){ p.stack = t.settings.stack; p.buyins++; t.L(`${p.name} se reîncarcă cu ${p.stack}.`, 'st'); t.kick(); t.push(); }
      break;
    case 'chat': {
      const text = clean(m.text, 200);
      if (text && Date.now() - (p.lastChat || 0) > 600){ p.lastChat = Date.now(); t.chat.push({ name:p.name, text }); if (t.chat.length > 60) t.chat.shift(); t.push(); }
      break;
    }
    case 'leave':
      t.L(`${p.name} a plecat de la masă.`);
      p.ws = null; p.connected = false; ws.data.table = null;
      t.dropJarvis(p, 'Ai plecat de la masă.');
      t.removeSeat(i); t.fixHost(); t.push();
      break;
  }
}

setInterval(() => {
  const now = Date.now();
  for (const [code, t] of tables){
    if (!t.humansOnline() && now - t.idle > 20 * 60 * 1000){ t.clear(); tables.delete(code); }
  }
}, 60 * 1000);

Bun.serve({
  port: PORT,
  fetch(req, server){
    const u = new URL(req.url);
    if (u.pathname === '/ws'){ if (server.upgrade(req, { data:{ table:null, id:null } })) return; return new Response('Upgrade failed', { status:400 }); }
    if (u.pathname === '/health') return new Response('ok');
    if (u.pathname === '/' || u.pathname === '/index.html'){
      if (!CLIENT) return loadClient().then(() => CLIENT
        ? new Response(CLIENT, { headers:{ 'Content-Type':'text/html; charset=utf-8', 'Content-Encoding':'gzip', 'Cache-Control':'no-cache' } })
        : new Response('Pagina jocului se încarcă. Reîncearcă în câteva secunde.', { status:503, headers:{ 'Content-Type':'text/plain; charset=utf-8' } }));
      return new Response(CLIENT, { headers:{ 'Content-Type':'text/html; charset=utf-8', 'Content-Encoding':'gzip', 'Cache-Control':'no-cache' } });
    }
    return new Response('Not found', { status:404 });
  },
  websocket: {
    idleTimeout: 120,
    message(ws, msg){ handle(ws, typeof msg === 'string' ? msg : new TextDecoder().decode(msg)); },
    close(ws){
      const d = ws.data; if (!d || !d.table) return;
      const t = tables.get(d.table); if (!t) return;
      if (d.jarvis){ const i = t.seatIdx(d.id); if (i >= 0 && t.seats[i].jv){ t.seats[i].jv.delete(ws); t.push(); } return; }
      t.disconnect(d.id, ws);
    }
  }
});
console.log(`Poker server on port ${PORT}`);
