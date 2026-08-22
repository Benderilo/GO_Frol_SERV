package store

// User — учётная запись администратора.
type User struct {
	ID           int64  `json:"id"`
	Login        string `json:"login"`
	PasswordHash string `json:"-"`
	DisplayName  string `json:"displayName"`
	Role         string `json:"role"`
	CreatedAt    string `json:"createdAt"`
	UpdatedAt    string `json:"updatedAt"`
}

// SiteContent — всё, что видно на публичной странице и редактируется из приложения.
type SiteContent struct {
	SiteName    string      `json:"siteName"`
	Tagline     string      `json:"tagline"`
	Ticker      Ticker      `json:"ticker"`
	Hero        Hero        `json:"hero"`
	About       About       `json:"about"`
	Services    []Service   `json:"services"`
	Advantages  []Advantage `json:"advantages"`
	Contacts    Contacts    `json:"contacts"`
	Appearance  Appearance  `json:"appearance"`
	FooterNote  string      `json:"footerNote"`
	Revision    int64       `json:"revision"`
	UpdatedAt   string      `json:"updatedAt"`
}

type Ticker struct {
	Enabled bool   `json:"enabled"`
	Text    string `json:"text"`
	SpeedSec int   `json:"speedSec"`
}

type Hero struct {
	Title      string `json:"title"`
	Subtitle   string `json:"subtitle"`
	PrimaryCta string `json:"primaryCta"`
	SecondaryCta string `json:"secondaryCta"`
	Badge      string `json:"badge"`
}

type About struct {
	Title string `json:"title"`
	Text  string `json:"text"`
}

type Service struct {
	Icon        string `json:"icon"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Price       string `json:"price"`
}

type Advantage struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

type Contacts struct {
	Phone     string `json:"phone"`
	Email     string `json:"email"`
	Address   string `json:"address"`
	Telegram  string `json:"telegram"`
	WhatsApp  string `json:"whatsapp"`
	WorkHours string `json:"workHours"`
}

type Appearance struct {
	Accent      string `json:"accent"`      // основной цвет, hex
	AccentAlt   string `json:"accentAlt"`   // второй цвет градиента, hex
	DefaultMode string `json:"defaultMode"` // auto | light | dark
}

// Client — карточка клиента CRM.
type Client struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Phone     string `json:"phone"`
	Email     string `json:"email"`
	Address   string `json:"address"`
	Note      string `json:"note"`
	Tag       string `json:"tag"`
	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`

	// Доступ в кабинет на сайте. Сам код наружу не отдаём — только хеш в БД.
	PortalCodeHash  string `json:"-"`
	PortalEnabled   bool   `json:"portalEnabled"`
	PortalLastLogin string `json:"portalLastLogin"`
}

// Order — заказ/работа по клиенту.
type Order struct {
	ID          int64   `json:"id"`
	ClientID    *int64  `json:"clientId"`
	ClientName  string  `json:"clientName"`
	Title       string  `json:"title"`
	Description string  `json:"description"`
	Status      string  `json:"status"`
	Price       float64 `json:"price"`
	DueDate     string  `json:"dueDate"`
	CreatedAt   string  `json:"createdAt"`
	UpdatedAt   string  `json:"updatedAt"`

	// Заполняется там, где заказ отдаётся с фотографиями.
	Photos     []OrderPhoto `json:"photos,omitempty"`
	PhotoCount int          `json:"photoCount"`
}

// OrderPhoto — снимок по заказу. Файл лежит на диске, здесь только метаданные.
// Token — непредсказуемый идентификатор в адресе картинки.
type OrderPhoto struct {
	ID        int64  `json:"id"`
	OrderID   int64  `json:"orderId"`
	Token     string `json:"token"`
	Path      string `json:"-"`
	ThumbPath string `json:"-"`
	Mime      string `json:"mime"`
	Size      int64  `json:"size"`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
	Caption   string `json:"caption"`
	Sort      int    `json:"sort"`
	CreatedAt string `json:"createdAt"`

	// URL заполняются на уровне API, чтобы клиенты не собирали пути сами.
	URL      string `json:"url"`
	ThumbURL string `json:"thumbUrl"`
}

// Request — заявка, пришедшая с формы на сайте.
type Request struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Phone     string `json:"phone"`
	Message   string `json:"message"`
	Source    string `json:"source"`
	Status    string `json:"status"`
	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`
}

// Stats — сводка для дашборда в приложении.
type Stats struct {
	Clients       int64   `json:"clients"`
	Orders        int64   `json:"orders"`
	OrdersActive  int64   `json:"ordersActive"`
	OrdersDone    int64   `json:"ordersDone"`
	RequestsNew   int64   `json:"requestsNew"`
	RevenueTotal  float64 `json:"revenueTotal"`
	RevenueActive float64 `json:"revenueActive"`
}
