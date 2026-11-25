# Alertă Apel

Telefonul A (care primește apelurile) trimite prin Bluetooth o alertă către telefonul B când sună.
B afișează notificare cu sunet + vibrație, numărul și numele din agendă. Trimite și „apel pierdut”.

## Construire APK

**Varianta 1 – Android Studio:** File → Open → folderul `AlertaApel` → așteaptă sincronizarea Gradle → Build → Build APK(s).

**Varianta 2 – fără Android Studio (GitHub):** urcă folderul într-un repo GitHub (privat e ok).
Workflow-ul din `.github/workflows/build.yml` construiește APK-ul automat; îl descarci din tab-ul Actions → ultimul build → Artifacts.

## Instalare și configurare

1. Instalează același APK pe ambele telefoane (permite „instalare din surse necunoscute”).
2. Împerechează telefoanele: Setări → Bluetooth (o singură dată).
3. **Telefonul B (cel care te anunță):** deschide aplicația → „Mă anunță” → Pornește.
4. **Telefonul A (cel care primește apelurile):** „Primește apelurile” → Alege telefonul B din listă → Pornește → acceptă permisiunile (Telefon, Jurnal apeluri, Contacte, Bluetooth, Notificări).
5. Pe A apasă „Trimite o alertă de test” – pe B trebuie să apară „Test reușit ✔”.
6. Pe ambele: scoate aplicația din optimizarea bateriei (butonul din app) și, pe Samsung,
   Setări → Aplicații → Alertă Apel → Baterie → **Fără restricții**.

## Detalii tehnice

- Bluetooth Clasic (RFCOMM, conexiune securizată între dispozitive împerecheate). B ține un server deschis; A se conectează doar când are ceva de trimis (consum minim).
- Raza: ~10 m prin pereți, până la ~30 m în câmp deschis.
- Serviciile pornesc automat după restartul telefonului.
- Mesaje: CALL (sună), ANSWERED (ai răspuns pe A → B șterge alerta), MISSED (pierdut), ENDED, TEST.
