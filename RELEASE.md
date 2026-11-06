# Publikacja na Maven Central

Ściąga dla każdego, kto chce wydać tę bibliotekę (lub własną) do Maven Central przez
[Central Portal](https://central.sonatype.com).

## Skrót (gdy wszystko jest już skonfigurowane)

```powershell
# 1. <version> w pom.xml bez -SNAPSHOT, np. 1.0.1
$env:MAVEN_GPG_PASSPHRASE = "haslo-do-klucza-gpg"
mvn -Prelease clean deploy
# 2. https://central.sonatype.com/publishing/deployments -> Validated -> Publish
# 3. po ~10-30 min: https://repo1.maven.org/maven2/io/github/kamarros/nbp-spring-boot-starter/
```

---

## Co trzeba mieć (jednorazowo)

### 1. Narzędzia
- JDK 25 (`java -version`) i Maven 3.9+ (`mvn -v`)
- GnuPG. Na Windows: `winget install GnuPG.Gpg4win`, potem **nowy terminal / restart IDE**.

### 2. Konto i namespace w Central Portal
1. Załóż konto na https://central.sonatype.com. Zapamiętaj, którą metodą się logujesz
   (e-mail / GitHub / Google): to osobne konta, a namespace należy tylko do jednego z nich.
2. *Namespaces* -> **Add Namespace**:
   - `io.github.<login-github>`: weryfikacja przez utworzenie **publicznego** repo GitHub
     o nazwie równej kluczowi weryfikacyjnemu (np. `github.com/kamarros/flf6h6amaq`) -> **Verify**.
   - własna domena (`com.firma`): weryfikacja rekordem TXT w DNS.
3. Status musi być **Verified**. `groupId` w `pom.xml` musi zaczynać się od tego namespace.

> Zmiana loginu na GitHubie **nie** przenosi namespace. Nowy login = nowy namespace do zweryfikowania.
> „Namespace exists” przy dodawaniu = jest już na innym Twoim koncie (sprawdź inne metody logowania)
> albo cudzym. Wtedy: central-support@sonatype.com.

### 3. User Token (login Mavena do Central)
1. Portal -> avatar -> **View Account** -> **Generate User Token**.
2. Skopiuj `username` i `password` od razu (pokazywane raz; nowy token unieważnia stary).
   To **nie** jest e-mail ani hasło do portalu.

### 4. Klucz GPG (podpis artefaktów)
```powershell
gpg --full-generate-key                        # RSA and RSA, 4096, imię, e-mail, hasło
gpg --list-secret-keys --keyid-format=long     # ID = ciąg po "rsa4096/"
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```
Sprawdzenie, czy klucz publiczny jest na serwerze (po kilku minutach):
`https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<FINGERPRINT>`

Zrób kopię zapasową klucza (`gpg --armor --export-secret-keys <KEY_ID> > private.asc`)
i trzymaj ją poza repozytorium.

### 5. `~/.m2/settings.xml`
```xml
<settings>
  <servers>
    <server>
      <id>central</id>                       <!-- musi pasować do publishingServerId w pom.xml -->
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <!-- pełna ścieżka = działa niezależnie od PATH terminala/IDE -->
        <gpg.executable>C:\Program Files\GnuPG\bin\gpg.exe</gpg.executable>
        <gpg.keyname>FINGERPRINT_KLUCZA</gpg.keyname>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```
Na Linux/macOS `gpg.executable` zwykle nie jest potrzebne.

---

## Co musi mieć `pom.xml` (wymagania Central)

Wszystko jest już w tym projekcie; lista przydaje się przy nowych bibliotekach.

| Element | Uwagi |
|---|---|
| `groupId`, `artifactId`, `version` | `groupId` w zweryfikowanym namespace; wersja wydania bez `-SNAPSHOT` |
| `name`, `description`, `url` | wymagane |
| `licenses` | np. Apache 2.0 |
| `developers` | co najmniej jeden |
| `scm` | `connection`, `developerConnection`, `url` |
| `-sources.jar` | `maven-source-plugin` |
| `-javadoc.jar` | `maven-javadoc-plugin` (javadoc musi się generować bez błędów) |
| podpisy `.asc` | `maven-gpg-plugin` |
| upload | `central-publishing-maven-plugin` z `<extensions>true</extensions>` i `publishingServerId=central` |

Tutaj pluginy wydania siedzą w profilu `release`, więc zwykłe `mvn install` nie wymaga GPG.
`autoPublish=false`: po uploadzie trzeba ręcznie kliknąć Publish.

---

## Każde wydanie, krok po kroku

1. Zmień `<version>` w `pom.xml` na wersję wydania, np. `1.0.1`. Zaktualizuj wersję w `README.md`.
2. Testy: `mvn clean verify`.
3. Próba bez wysyłki: `mvn -Prelease clean verify`. W `target/` muszą być `.jar`,
   `-sources.jar`, `-javadoc.jar` i `.asc` przy każdym.
4. Wysyłka:
   ```powershell
   $env:MAVEN_GPG_PASSPHRASE = "haslo-do-klucza-gpg"
   mvn -Prelease clean deploy
   ```
   Oczekiwane: `Uploaded bundle successfully` i `BUILD SUCCESS`.
5. https://central.sonatype.com/publishing/deployments:
   - status **Validated** -> sprawdź pliki -> **Publish**
   - status **Failed** -> przeczytaj błędy, **Drop**, popraw, wróć do pkt 4
6. **Publish jest nieodwracalny**: tej wersji nie da się zmienić ani usunąć. Poprawka = nowa wersja.
7. Commit + tag: `git tag v1.0.1 && git push origin v1.0.1`.
8. Podbij wersję na `1.0.2-SNAPSHOT` do dalszej pracy.

### Gdzie i kiedy widać wydanie

| Miejsce | Czas | Link |
|---|---|---|
| Maven Central (Maven pobiera stąd) | ~10-30 min | https://repo1.maven.org/maven2/io/github/kamarros/nbp-spring-boot-starter/ |
| Wyszukiwarka Central | do kilku godzin | https://central.sonatype.com/artifact/io.github.kamarros/nbp-spring-boot-starter |
| mvnrepository.com | godziny-dni | https://mvnrepository.com/artifact/io.github.kamarros/nbp-spring-boot-starter |

---

## Testowanie lokalnie w innym projekcie (bez publikacji)

```powershell
mvn clean install        # instaluje do ~/.m2/repository
```
W drugim projekcie dodaj zależność z tą samą wersją. Najlepiej pracuj na `-SNAPSHOT`, bo lokalna
kopia wersji wydania w `~/.m2` przesłania tę z Central. Żeby po publikacji przetestować
prawdziwy artefakt z Central, usuń
`~/.m2/repository/io/github/kamarros/nbp-spring-boot-starter/<wersja>` i zbuduj z `-U`.

---

## Typowe błędy

| Błąd | Przyczyna / rozwiązanie |
|---|---|
| `Could not determine gpg version` | Maven nie widzi `gpg`. Ustaw `gpg.executable` w `settings.xml` albo zrestartuj terminal/IDE po instalacji GnuPG. |
| `401 Unauthorized` przy uploadzie | Brak/zły token w `settings.xml`, `id` serwera inny niż `central`, albo token z innego konta niż to z namespace. |
| `Namespace ... is not allowed` / brak uprawnień | `groupId` nie należy do zweryfikowanego namespace na koncie, z którego jest token. |
| `Invalid signature` / `Could not find a public key` | Klucz publiczny nie wysłany na `keyserver.ubuntu.com` (lub nie zdążył się rozpropagować). |
| `Javadocs must be provided` / błąd javadoc | Javadoc nie wygenerował się; popraw komentarze lub uruchom z profilem `release`. |
| `Component with package url ... already exists` | Ta wersja jest już opublikowana; podbij `<version>`. |
| Okienko z prośbą o hasło GPG | Nie ustawiono `MAVEN_GPG_PASSPHRASE` w tym samym terminalu co `mvn`. |

---

## Opcjonalnie: publikacja z GitHub Actions

`.github/workflows/publish.yml` publikuje po wypchnięciu tagu `v*` (wersja w tagu = `<version>`).
Sekrety repozytorium (*Settings -> Secrets and variables -> Actions*):

| Sekret | Wartość |
|---|---|
| `CENTRAL_USERNAME` | username z User Tokena |
| `CENTRAL_PASSWORD` | password z User Tokena |
| `GPG_PRIVATE_KEY` | wynik `gpg --armor --export-secret-keys <KEY_ID>` |
| `GPG_PASSPHRASE` | hasło do klucza |
