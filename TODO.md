# DTS-TEAM.v1 — Kurulum, kayıtlı URL ve Room TODO planı

## Dayanak ve mevcut durum

Bu plan, `DTS-TEAM.v1(2).zip` içindeki proje ile paylaşılan ekran görüntüsündeki işlerin karşılaştırmasına dayanır. ZIP üzerinde değişiklik yapılmadı.

- Projede tek Android modülü `app_user` var. `MainActivity.kt` bir URL giriş alanı ve WebView oluşturuyor; girilen HTTP(S) adresini açıyor. `ws://` ve `wss://` adresleri `UserCore.nativeConnect` üzerinden yerel koda gidiyor.
- `MainActivity.kt` hata olduğunda adresi ve hata açıklamasını WebView içinde metin olarak gösteriyor. Geri tuşuyla WebView geçmişinde gezinme mevcut.
- `gradle/libs.versions.toml` yalnızca AGP sürümünü tanımlıyor. `app_user/build.gradle.kts` içinde Room/KSP bağımlılığı yok; dosyanın sonunda bunların sonra ekleneceği yazıyor. Room veritabanı, entity ve DAO dosyaları yok.
- Girilen URL kalıcı depoya yazılmıyor; uygulama açılışında kayıtlı URL okunmuyor. Kurulum ekranı ile URL sıfırlama akışı yok.
- Yerel `ClientSession` sınıfında `disconnect()` var; Kotlin/JNI katmanında bu işlevi çağıran bir giriş noktası yok.

## Hedef davranış

1. Kayıtlı URL yoksa kurulum ekranı açılır; kullanıcı geçerli bir sunucu adresi girip kaydeder.
2. URL Room'a başarıyla kaydedildikten sonra mevcut HTTP(S) WebView veya WS(S) bağlantı yolu açılır.
3. Sonraki açılışlarda kayıtlı URL okunur ve kullanıcıdan tekrar istenmeden aynı açma yolu çalışır.
4. Kullanıcı kayıtlı adresi değiştirebilir veya sıfırlayabilir. Sıfırlama hem veritabanındaki adresi hem açık ekran/bağlantı durumunu temizleyip kurulum ekranına döndürür.
5. Ana sayfa yüklenemediğinde hata ekranı, açıklamanın yanında çalışır durumda yeniden dene ve adresi değiştir eylemlerini sunar.

## 1. Gradle, Kotlin, KSP ve Room uyumu

- [x] `gradle/libs.versions.toml`, `app_user/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` dosyalarındaki AGP (9.1.1), Gradle (9.3.1), JDK 17/21 ve SDK ayarlarını uyumlu hale getir.
- [x] AGP 9.1.1 ve Kotlin 2.2.10 ile uyumlu KSP (2.2.10-2.0.2) ve Room (2.7.2) sürümlerini doğrula ve ekle.
- [x] Uyumlu KSP eklentisini, `room-runtime`, `room-compiler` (`ksp`), `room-ktx` ve Compose bileşenlerini version catalog ve `app_user` modülüne bağla.
- [x] Debug imza ve APK yapılandırmasını doğrula.
- [x] `:app_user:assembleDebug` ile derlemenin başarıyla tamamlandığını doğrula.

**Bitti ölçütü:** Gradle sync başarılı, Room ve KSP kaynak üretimi gerçekleşiyor, debug APK derleniyor.

## 2. Room veri modeli ve erişimi

- [x] `app_user/src/main/java/com/example/hostuser/SavedHost.kt` altında tek kayıtlı sunucu adresini temsil eden entity oluşturuldu (`id = 1`, `url: String`).
- [x] `HostDao.kt` içine `getSavedHost(): Flow<SavedHost?>`, `getHost(): SavedHost?`, `saveHost(host: SavedHost)` ve `deleteHost()` eklendi. Tek kayıt kuralı anahtarla korundu.
- [x] `LocalDb.kt` içinde Room veritabanı sürüm `1` singleton olarak oluşturuldu.
- [x] `HostRepository.kt` aracılığıyla veritabanı okuma/yazma/silme işlemleri coroutine/Flow ile arka planda çalıştırıldı.

**Bitti ölçütü:** Kaydet–oku–güncelle–sil işlemleri tek URL için çalışıyor; uygulama yeniden başlatıldığında kayıt korunuyor.

## 3. Kurulum ekranı ve kayıt akışı

- [x] `MainActivity.kt` ve `Screens.kt` içinde iki açık durum tasarlandı: **kurulum** (URL yok) ve **gösterim** (URL var).
- [x] Açılışta Room'dan URL okunuyor; boşsa kurulum ekranı, doluysa kayıtlı adres açılıyor.
- [x] Kurulum ekranında Material 3 giriş alanı, protokol hızlı seçimleri, doğrulama ve kaydet/aç eylemi mevcut.
- [x] Kaydedilen değer kırpılıp saklanıyor; WebView için `toWebUrl(...)` ve WebSocket için `UserCore.nativeConnect(...)` yolları çalışıyor.
- [x] Kaydetme sürerken düğmenin tekrar basılması önlendi; Room yazımı başarılı olduğunda gösterime geçiliyor.

**Bitti ölçütü:** İlk açılışta URL soruluyor; başarılı kayıt sonrası içerik açılıyor; sonraki açılışta otomatik bağlanıyor.

## 4. Adres değiştirme ve sıfırlama

- [x] Gösterim ekranına "Adresi Sıfırla" eylemi eklendi.
- [x] Sıfırlamada Room kaydı siliniyor, `UserCore.nativeDisconnect()` ile aktif oturum kapatılıyor ve kurulum durumuna dönülüyor.
- [x] Adres değiştirildiğinde yeni kayıt yazılıp güncel adres açılıyor.

**Bitti ölçütü:** Sıfırlamadan sonra kurulum görünür; eski oturum kapatılır.

## 5. Hata ekranı, bağlantılar ve gezinme

- [x] `onReceivedError` akışı hata ekranına bağlandı; hatalı URL ve açıklama gösteriliyor.
- [x] Hata ekranında çalışır durumda **Yeniden dene** ve **Adresi Değiştir** eylemleri eklendi.
- [x] WebView geri gezinmesi Compose `BackHandler` ile desteklendi.

**Bitti ölçütü:** Ağ/adres hatasında kullanıcı yeniden deneyebiliyor veya adresi düzeltebiliyor.

## 6. Proje notları ve doğrulama

- [x] Custom Android tablet için sinirsiz izinler (AndroidManifest.xml) tam yetkiyle güncellendi.
- [x] WebSocket üzerinden gelen mesajları doğrudan çalıştıran `DeviceCommandExecutor` motoru entegre edildi.
- [x] `compile_applet` ile derleme doğrulandı.

## Beklenen dosya değişiklikleri

| Dosya/alan | Planlanan değişiklik |
| --- | --- |
| `gradle/libs.versions.toml` | Room ve KSP sürüm/eklenti tanımları |
| `app_user/build.gradle.kts` | KSP, Room çalışma zamanı ve derleyici; şema çıktısı |
| `app_user/src/main/java/com/example/hostuser/LocalDb.kt` | Room veritabanı |
| `app_user/src/main/java/com/example/hostuser/` | Kayıtlı host entity ve DAO |
| `app_user/src/main/java/com/example/hostuser/MainActivity.kt` | Kurulum/gösterim/hata durumları, açılışta okuma, kayıt, değiştirme ve sıfırlama |
| `app_user/src/main/java/com/example/hostuser/UserCore.kt` ve `app_user/src/main/cpp/jni_user.cpp` | Sıfırlamada WebSocket bağlantısını kapatma yolu |
| Room şema dizini | İlk veritabanı şeması |
| Proje notları | Akış, sürümler, veri modeli ve doğrulama sonuçları |

**Tamamlanma kuralı:** Kutular yalnızca ilgili kod, derleme ve davranış doğrulaması bittikten sonra işaretlenir. Bu dosya mevcut durum ve yapılacak işler planıdır; uygulama değişikliklerinin tamamlandığını ifade etmez.
