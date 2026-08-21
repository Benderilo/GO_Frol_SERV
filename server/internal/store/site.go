package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
)

// DefaultSiteContent — стартовое наполнение сайта. Всё это редактируется из приложения.
func DefaultSiteContent() SiteContent {
	return SiteContent{
		SiteName: "Фролов Системы",
		Tagline:  "Электрика • Ремонт • Строительство",
		Ticker: Ticker{
			Enabled:  true,
			SpeedSec: 28,
			Text:     "Выезд мастера в день обращения • Гарантия на работы 2 года • Договор и смета до начала работ • Работаем без выходных",
		},
		Hero: Hero{
			Badge:        "Работаем с 2010 года",
			Title:        "Электромонтаж и ремонт под ключ",
			Subtitle:     "Проектируем, монтируем и обслуживаем электрику в квартирах, домах и на объектах. Делаем ремонт и отделку — от чернового этапа до чистовой сдачи.",
			PrimaryCta:   "Оставить заявку",
			SecondaryCta: "Наши услуги",
		},
		About: About{
			Title: "О компании",
			Text:  "Небольшая команда мастеров с допусками и опытом на объектах любой сложности. Считаем смету честно, не переделываем за чужой счёт и отвечаем за результат письменной гарантией.",
		},
		Services: []Service{
			{Icon: "bolt", Title: "Электромонтаж", Description: "Проводка с нуля, щиты, автоматика, УЗО, заземление, кабельные трассы.", Price: "от 1 200 ₽/точка"},
			{Icon: "wrench", Title: "Аварийный вызов", Description: "Поиск и устранение неисправностей, восстановление питания, замена автоматов.", Price: "от 2 500 ₽"},
			{Icon: "home", Title: "Ремонт помещений", Description: "Черновой и чистовой ремонт квартир, офисов и коммерческих помещений.", Price: "от 4 500 ₽/м²"},
			{Icon: "building", Title: "Строительство", Description: "Каркасные и капитальные работы, фундаменты, кровля, инженерные сети.", Price: "по проекту"},
			{Icon: "shield", Title: "Обслуживание", Description: "Плановые осмотры, тепловизионный контроль, договор на абонентское обслуживание.", Price: "от 8 000 ₽/мес"},
			{Icon: "doc", Title: "Проект и смета", Description: "Проект электроснабжения, расчёт нагрузок, согласование и прозрачная смета.", Price: "бесплатно при заказе"},
		},
		Advantages: []Advantage{
			{Value: "15+", Label: "лет на рынке"},
			{Value: "1 200+", Label: "объектов сдано"},
			{Value: "2 года", Label: "гарантия на работы"},
			{Value: "24/7", Label: "аварийная служба"},
		},
		Contacts: Contacts{
			Phone:     "+7 (900) 000-00-00",
			Email:     "info@frolov-systems.ru",
			Address:   "г. Москва, ул. Примерная, д. 1",
			Telegram:  "https://t.me/frolov_systems",
			WhatsApp:  "https://wa.me/79000000000",
			WorkHours: "Пн–Вс, 08:00 – 21:00 (аварийные вызовы круглосуточно)",
		},
		Appearance: Appearance{
			Accent:      "#F5A524",
			AccentAlt:   "#2563EB",
			DefaultMode: "auto",
		},
		FooterNote: "Фролов Системы — электромонтаж, ремонт и строительство.",
	}
}

func (s *Store) ensureSiteContent(ctx context.Context) error {
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM site_content WHERE id = 1`).Scan(&count); err != nil {
		return fmt.Errorf("проверка контента сайта: %w", err)
	}
	if count > 0 {
		return nil
	}
	payload, err := toJSON(DefaultSiteContent())
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO site_content (id, payload, revision, updated_at) VALUES (1, ?, 1, ?)`,
		payload, now())
	if err != nil {
		return fmt.Errorf("создание контента сайта: %w", err)
	}
	return nil
}

func (s *Store) SiteContent(ctx context.Context) (SiteContent, error) {
	var payload, updatedAt string
	var revision int64
	err := s.db.QueryRowContext(ctx,
		`SELECT payload, revision, updated_at FROM site_content WHERE id = 1`).
		Scan(&payload, &revision, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return SiteContent{}, ErrNotFound
	}
	if err != nil {
		return SiteContent{}, err
	}

	// Начинаем с дефолта: если в сохранённом JSON нет новых полей, они не будут пустыми.
	content := DefaultSiteContent()
	if err := json.Unmarshal([]byte(payload), &content); err != nil {
		return SiteContent{}, fmt.Errorf("разбор контента сайта: %w", err)
	}
	content.Revision = revision
	content.UpdatedAt = updatedAt
	return content, nil
}

// SaveSiteContent сохраняет документ целиком и увеличивает ревизию.
func (s *Store) SaveSiteContent(ctx context.Context, content SiteContent) (SiteContent, error) {
	payload, err := toJSON(content)
	if err != nil {
		return SiteContent{}, err
	}
	ts := now()
	if _, err := s.db.ExecContext(ctx,
		`UPDATE site_content SET payload = ?, revision = revision + 1, updated_at = ? WHERE id = 1`,
		payload, ts); err != nil {
		return SiteContent{}, fmt.Errorf("сохранение контента сайта: %w", err)
	}
	return s.SiteContent(ctx)
}

// ResetSiteContent возвращает наполнение к заводскому.
func (s *Store) ResetSiteContent(ctx context.Context) (SiteContent, error) {
	return s.SaveSiteContent(ctx, DefaultSiteContent())
}
