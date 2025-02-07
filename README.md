# 2023-nosql-lsm
Проект [курса]() "NoSQL"

## Этап 1. In-memory (deadline 27.09.23 23:59:59 MSK)
### Fork
[Форкните проект](https://help.github.com/articles/fork-a-repo/), склонируйте и добавьте `upstream`:
```
$ git clone git@github.com:<username>/2023-nosql-lsm.git
Cloning into '2023-nosql-lsm'...
...
$ git remote add upstream git@github.com:polis-vk/2023-nosql-lsm.git
$ git fetch upstream
From github.com:polis-vk/2023-nosql-lsm
 * [new branch]      main     -> upstream/main
```

### Make
Так можно запустить тесты (ровно то, что делает CI):
```
$ ./gradlew clean test
```

### Develop
Откройте в IDE -- [IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/) нам будет достаточно.

**ВНИМАНИЕ!** При запуске тестов или сервера в IDE необходимо передавать Java опцию `-Xmx64m`.

Сделать имплементацию интерфейса DAO, заставив пройти все тесты.
Для этого достаточно реализовать две операции: get и upsert, при этом достаточно реализации "в памяти".

Продолжайте запускать тесты и исправлять ошибки, не забывая [подтягивать новые тесты и фиксы из `upstream`](https://help.github.com/articles/syncing-a-fork/). Если заметите ошибку в `upstream`, заводите баг и присылайте pull request ;)

### Report
Когда всё будет готово, присылайте pull request в ветку `main` со своей реализацией на review. Не забывайте **отвечать на комментарии в PR** и **исправлять замечания**!

## Этап 2. Persistence (deadline 2023-10-04 23:59:59 MSK)
Приведите код в состояние, удовлетворяющее новым тестам. А именно: при конструировании DAO следует восстановить состояние, персистентно сохраненное в методе `close()`.
Нужно реализовать метод `get(key)`, который будет возвращать `Entry`, сохраненной в памяти или в хипе.

В `DaoFactory.Factory` появился конструктор `createDao(Config config)`, который нужно переопределить в своей реализации.
`Config` Содержит в себе `basePath` - директория для сохранения состояния DAO.

В новых тестах не предполагается использование полноценной реализации метода `get(from, to)`.

### Report
Когда всё будет готово, присылайте pull request со своей реализацией на review. Не забывайте **отвечать на комментарии в PR** и **исправлять замечания**!

## Этап 3. Merge Iterator (deadline 2023-10-18 23:59:59 MSK)
Приведите код в состояние, удовлетворяющее данным запросам:
* Бинарный поиск по файлу теперь является обязательным для реализации.
* Метод `get(from, to)` должен работать полноценно и полностью поддерживаться DAO даже при наличии в ней данных, записанных в файл.
* Стоит учитывать, что в диапазоне от `from` до `to`, некая часть данных может уже находиться в файле на диске, в то время, как другая часть будет все еще `InMemory`. Несмотря на это, необходимо уметь последовательно отдавать все данные в правильно отсортированном порядке.
* Запрошенный диапазон значений может быть слишком велик, чтобы держать его полностью в памяти, нужно динамически подгружать лишь необходимую в данный момент часть этого диапазона.
* За один тест метод `close` у конкретного инстанса DAO может быть вызван только один раз, однако после этого имеется возможность создать новый инстанс с тем же `config`(т.е будет произведено переоткрытие DAO). В отличие от прошлого этапа, в этот раз в DAO можно будет писать новую информацию, а не только читать старую из него. Количество таких переоткрытий за один тест неограниченно. Следовательно, должна поддерживаться возможность создания соответствующего количества файлов, содержащих данные нашей БД - по одному на каждый `close`, а так же возможность поиска значений по всем этим файлам.
* После использования метода `close`, `Entry` с уже записанным до этого в файл ключом может быть добавлено в последующие DAO еще несколько раз, т.е текущее значение, соответствующее этому ключу, будет таким образом перезаписано, однако старые значения, соответствующие этому же ключу, все еще будут находиться в более ранних файлах. База данных должна суметь выдать самое свежее для запрошенного ключа значение при поиске из всех имеющихся.
* На данный момент метод `flush` будет вызываться только из метода `close`. Таким образом, `flush` будет гарантированно выполняться однопоточно, также на данном этапе можно рассчитывать на то, что `get` и `upsert` в процессе выполнения метода `flush` вызываться не будут, однако во все остальное время `get` и `upsert` могут работать в многопоточном режиме - старые тесты на `concurrency` никуда не пропадают, но могут появиться и новые. Считаем, что поведение `get` и `upsert` после отработки метода `close` - `undefined`
* `upsert` с `value == null` подразумевает удаление: такие записи не должны возвращаться в `get`.
### Report
Когда всё будет готово, присылайте pull request в ветку `main` со своей реализацией на review. Не забывайте **отвечать на комментарии в PR** и **исправлять замечания**!

## Этап 4. Compact (deadline 2023-11-01 23:59:59 MSK)
Требуется реализовать метод `compact()` в DAO:
* Все SSTable'ы должны быть слиты в один, включая как таблицы на диске, так и таблицу в памяти
* Старые должны быть удалены
* Должна сохраниться только самая свежая версия, старые записи должны быть удалены
* Удалённые записи (`upsert` с `null`-значением) не должны сохраняться при `сompact()`
* В рамках данного задания гарантируется, что сразу после `compact()` будет вызван метод `close()`, а значит работа с этим DAO больше производиться не будет. Но в последствии может быть открыто новое `DAO` c тем же конфигом. И на нём тоже может быть вызван `compact`.
* `close()` обязан быть идемпотентным -- повторный `close()` не должен приводить к отрицательным последствиям, включая потерю производительности. На текущем этапе можно считать, что конкуретных вызовов `close()` нет.
* Тесты будут появляться и дополняться
### Report
Когда всё будет готово, присылайте pull request в ветку `main` со своей реализацией на review. Не забывайте **отвечать на комментарии в PR** и **исправлять замечания**!
