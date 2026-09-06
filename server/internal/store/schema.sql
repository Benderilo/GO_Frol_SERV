PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    login         TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    display_name  TEXT    NOT NULL DEFAULT '',
    role          TEXT    NOT NULL DEFAULT 'admin',
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

-- Контент сайта хранится одним JSON-документом: так приложение может
-- редактировать любую секцию целиком, без миграций под каждое поле.
CREATE TABLE IF NOT EXISTS site_content (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    payload    TEXT    NOT NULL,
    revision   INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT    NOT NULL
);

-- Реквизиты ИП: одна строка на всю базу. Лежат единым JSON по той же причине,
-- что и наполнение сайта: поля тут добавляются часто, а миграция на каждое —
-- лишняя работа. В печатные документы подставляются отсюда.
CREATE TABLE IF NOT EXISTS company (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    payload    TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS clients (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    phone      TEXT    NOT NULL DEFAULT '',
    email      TEXT    NOT NULL DEFAULT '',
    address    TEXT    NOT NULL DEFAULT '',
    note       TEXT    NOT NULL DEFAULT '',
    tag        TEXT    NOT NULL DEFAULT '',
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    -- Доступ клиента в личный кабинет: код входа хранится хешем.
    portal_code_hash  TEXT    NOT NULL DEFAULT '',
    portal_enabled    INTEGER NOT NULL DEFAULT 0,
    portal_last_login TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_clients_name ON clients(name);

-- Деньги везде в копейках целым числом. Дробное REAL для сумм не годится:
-- 1999.99 в двоичной дроби не представимо точно, и сумма ста платежей
-- перестаёт совпадать с итогом — в сверке с клиентом это всплывает первым.
CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id   INTEGER REFERENCES clients(id) ON DELETE SET NULL,
    title       TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    status      TEXT    NOT NULL DEFAULT 'new',
    price_kop   INTEGER NOT NULL DEFAULT 0,
    due_date    TEXT    NOT NULL DEFAULT '',
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    closed_at   TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_client ON orders(client_id);
CREATE INDEX IF NOT EXISTS idx_orders_closed ON orders(closed_at);

-- Касса: одна книга на все деньги. Оплата по заказу — такой же приход,
-- как и любой другой, просто со ссылкой на заказ. Держать платежи отдельно
-- от расходов значило бы считать остаток по двум таблицам и однажды забыть
-- про одну из них.
--
-- Сумма всегда положительная, знак несёт direction: так отчёт по статьям
-- складывается без разбора знаков, а «расход −500» нельзя ввести случайно.
CREATE TABLE IF NOT EXISTS cash_ops (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    direction   TEXT    NOT NULL DEFAULT 'in',   -- in | out
    amount_kop  INTEGER NOT NULL,
    method      TEXT    NOT NULL DEFAULT 'cash', -- cash | card | account
    category    TEXT    NOT NULL DEFAULT '',
    order_id    INTEGER REFERENCES orders(id) ON DELETE SET NULL,
    client_id   INTEGER REFERENCES clients(id) ON DELETE SET NULL,
    note        TEXT    NOT NULL DEFAULT '',
    -- Когда деньги двинулись. Может отличаться от момента записи:
    -- расход часто вносят вечером, а был он утром.
    happened_at TEXT    NOT NULL,
    created_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cash_order ON cash_ops(order_id);
CREATE INDEX IF NOT EXISTS idx_cash_happened ON cash_ops(happened_at);
CREATE INDEX IF NOT EXISTS idx_cash_direction ON cash_ops(direction, happened_at);

-- Заявки с формы на сайте.
-- Справочник того, что продаётся и расходуется: услуги, работы, материалы.
-- Вид хранится полем, а не тремя таблицами с одинаковым набором колонок:
-- отличие материала только в том, что у него есть склад.
CREATE TABLE IF NOT EXISTS catalog_items (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT    NOT NULL DEFAULT 'service',
    name       TEXT    NOT NULL,
    unit       TEXT    NOT NULL DEFAULT 'шт.',
    price_kop  INTEGER NOT NULL DEFAULT 0,
    cost_kop   INTEGER NOT NULL DEFAULT 0,
    note       TEXT    NOT NULL DEFAULT '',
    archived   INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_catalog_kind ON catalog_items(kind, name);

-- Движения материала: приход положительным количеством, списание отрицательным.
-- Остаток нигде не хранится, а считается как сумма движений: отдельное число
-- рано или поздно разошлось бы с историей, и найти, где именно, было бы нечем.
--
-- Количество — в тысячных долях единицы, целым числом, по той же причине,
-- по которой деньги в копейках: 0.1 в двоичной дроби не представима точно,
-- и остаток после сотни списаний перестал бы быть круглым.
CREATE TABLE IF NOT EXISTS stock_moves (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id    INTEGER NOT NULL REFERENCES catalog_items(id) ON DELETE CASCADE,
    order_id   INTEGER REFERENCES orders(id) ON DELETE SET NULL,
    qty_milli  INTEGER NOT NULL,
    cost_kop   INTEGER NOT NULL DEFAULT 0,
    note       TEXT    NOT NULL DEFAULT '',
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stock_item ON stock_moves(item_id, id);
CREATE INDEX IF NOT EXISTS idx_stock_order ON stock_moves(order_id);

-- Позиции заказа: из чего сложилась его цена. Итог заказа пересчитывается
-- сервером из этих строк, поэтому у заказа с составом orders.price_kop —
-- не отдельная правда, а сумма позиций.
--
-- Название и цена копируются в строку, а не берутся из справочника по ссылке:
-- позицию справочника потом переименуют или подорожает, а в выданном клиенту
-- документе должно остаться то, о чём договаривались.
CREATE TABLE IF NOT EXISTS order_items (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id      INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    catalog_id    INTEGER REFERENCES catalog_items(id) ON DELETE SET NULL,
    kind          TEXT    NOT NULL DEFAULT 'service',
    name          TEXT    NOT NULL,
    unit          TEXT    NOT NULL DEFAULT 'шт.',
    qty_milli     INTEGER NOT NULL DEFAULT 1000,
    price_kop     INTEGER NOT NULL DEFAULT 0,
    cost_kop      INTEGER NOT NULL DEFAULT 0,
    -- Движение склада, которым строка списана. Пусто — материал ещё на складе.
    stock_move_id INTEGER REFERENCES stock_moves(id) ON DELETE SET NULL,
    sort          INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id, sort, id);

-- Выданные документы. Номер закрепляется за парой «заказ + вид» навсегда:
-- перевыпуск того же счёта не должен порождать новый номер, иначе у клиента
-- на руках и в учёте окажутся разные счета на одну работу.
CREATE TABLE IF NOT EXISTS documents (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id  INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    kind      TEXT    NOT NULL,
    year      INTEGER NOT NULL,
    number    INTEGER NOT NULL,
    issued_at TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_order_kind ON documents(order_id, kind);
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_number ON documents(kind, year, number);

CREATE TABLE IF NOT EXISTS requests (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    phone      TEXT    NOT NULL DEFAULT '',
    message    TEXT    NOT NULL DEFAULT '',
    source     TEXT    NOT NULL DEFAULT 'site',
    status     TEXT    NOT NULL DEFAULT 'new',
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_requests_status ON requests(status);

-- Задачи/напоминания: follow-up по клиенту или заказу, со сроком и приоритетом.
-- parent_id — подзадачи без ограничения вложенности; удаление родителя
-- поднимает подзадачи уровнем выше (SET NULL), а не стирает их.
CREATE TABLE IF NOT EXISTS tasks (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id  INTEGER REFERENCES clients(id) ON DELETE SET NULL,
    order_id   INTEGER REFERENCES orders(id) ON DELETE SET NULL,
    parent_id  INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
    title      TEXT    NOT NULL,
    note       TEXT    NOT NULL DEFAULT '',
    due_date   TEXT    NOT NULL DEFAULT '',
    done       INTEGER NOT NULL DEFAULT 0,
    priority   TEXT    NOT NULL DEFAULT 'normal',
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(due_date);
CREATE INDEX IF NOT EXISTS idx_tasks_done ON tasks(done);
CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id);

-- Журнал действий: кто/что/когда менял в CRM.
CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT    NOT NULL,
    entity_id   INTEGER NOT NULL DEFAULT 0,
    action      TEXT    NOT NULL,
    detail      TEXT    NOT NULL DEFAULT '',
    created_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);

-- Фотографии по заказу: сам файл лежит на диске, в БД только метаданные.
CREATE TABLE IF NOT EXISTS order_photos (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id   INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    token      TEXT    NOT NULL UNIQUE,
    path       TEXT    NOT NULL,
    thumb_path TEXT    NOT NULL DEFAULT '',
    mime       TEXT    NOT NULL,
    size       INTEGER NOT NULL DEFAULT 0,
    width      INTEGER NOT NULL DEFAULT 0,
    height     INTEGER NOT NULL DEFAULT 0,
    caption    TEXT    NOT NULL DEFAULT '',
    sort       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_photos_order ON order_photos(order_id, sort, id);
