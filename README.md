# BeyHodHealthDisplay v6

Paper/Spigot 1.21.11 hedefli TextDisplay tabanlı can göstergesi.

## v6 Vanilla nametag, HUD şeffaflığı ve akıcılık düzeltmesi

- Nametag ile isim verilmiş moblarda vanilla Minecraft isim etiketi artık imleçle bakınca da görünmez.
- Mobun özel ismi HUD aktifken geçici olarak PDC’ye kaydedilip mobdan kaldırılır; HUD aynı ismi göstermeye devam eder.
- Gösterge kalkınca veya plugin kapanınca eski custom name ve `customNameVisible` durumu geri yüklenir.
- Önceki oturumda saklı kalmış isimler plugin açılışında otomatik geri yüklenir.
- TextDisplay arka planı her güncellemede tekrar şeffaf uygulanır.
- Hareket akıcılığı için interpolation/teleport değerleri varsayılan olarak `2` yapıldı.
- Görünürlük paketleri her tick yerine `visibility-refresh-interval-ticks` ile yenilenir; bu, kalabalık moblarda daha stabil görüntü sağlar.

## v5 Konum ve şeffaf arka plan düzeltmesi

- Yazılar mob/oyuncu kafasının üstünde daha yukarı taşındı.
- `display.height-offset` varsayılanı `1.05` yapıldı.
- TextDisplay varsayılan koyu arka planı kapatıldı.
- `display.transparent-background: true` ile arka plan tamamen şeffaf yapıldı.
- Yazı opaklığı için `display.text-opacity: 255` eklendi.
- İstersen şeffaflığı kapatıp `background-color` ve `background-alpha` ile yarı saydam arka plan kullanabilirsin.

## v3 İsim çakışması düzeltmesi

- Özel isimli moblarda vanilla isim etiketi gizlenir.
- Mob ismi artık sadece TextDisplay içinde bir kez görünür.
- Gösterge kaldırılınca mobun önceki vanilla isim görünürlüğü geri yüklenir.
- Ayar: `display.hide-vanilla-custom-name-while-displayed: true`

## v2 Akıcılık düzeltmesi

- Gösterge artık varsayılan olarak 1 tickte bir güncellenir.
- İlk açılış gecikmesi 20 tick yerine 1 ticktir.
- Hasar alınca gösterge bir sonraki tickte anında oluşturulur/güncellenir.
- TextDisplay interpolation/teleport smoothing ayarları eklendi.
- Eski configte update-interval-ticks 5 kalsa bile `performance.fast-mode: true` olduğu sürece 1 tick kullanılır.

## Derleme

```bat
build-plugin.bat
```

Jar:

```text
target/BeyHodHealthDisplay.jar
```

## Komutlar

```text
/hd toggle
/hd mode always|look|combat|combat_look
/hd reload
/hd cleanup
/hd debug
```
