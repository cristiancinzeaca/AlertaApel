// ===== serviciul „poker-web”: servește pagina jocului =====
// Pe Railway fișierul e: shared.js + web.js + pagina (client.html) pusă la final într-un comentariu /*__CLIENT__ ... __END__*/,
// cu //SHARED în loc de /*SHARED*/ și fără comentarii /* */ în pagină. Serverul de joc descarcă pagina de aici.
const SRC = await Bun.file(import.meta.path).text();
const SHARED_SRC = SRC.slice(SRC.indexOf('// ===== cod comun'), SRC.indexOf('// ===== sfârșitul codului comun'));
const CB = '/*' + '__CLIENT__', CE = '__END' + '__*/';
const HTML = SRC.slice(SRC.indexOf(CB) + CB.length, SRC.lastIndexOf(CE)).replace('//SHARED', () => SHARED_SRC);
Bun.serve({
  port: Number(Bun.env.PORT || 3000),
  fetch(){ return new Response(HTML, { headers:{ 'Content-Type':'text/html; charset=utf-8', 'Cache-Control':'no-cache' } }); }
});
console.log('poker-web gata');
