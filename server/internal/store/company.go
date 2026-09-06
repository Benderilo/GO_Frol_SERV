package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Company — реквизиты ИП. Подставляются в счёт, акт и перечень работ,
// поэтому здесь лежит всё, что печатается в шапке и подвале документа.
//
// Поля намеренно строковые: ИНН и счёт — не числа, а идентификаторы,
// у них значимы ведущие нули, и складывать их никто не будет.
type Company struct {
	// Как ИП называется в документах.
	ShortName string `json:"shortName"` // ИП Фролов А. В.
	FullName  string `json:"fullName"`  // Индивидуальный предприниматель Фролов Алексей Владимирович

	INN    string `json:"inn"`
	OGRNIP string `json:"ogrnip"`

	Address string `json:"address"`
	Phone   string `json:"phone"`
	Email   string `json:"email"`
	Site    string `json:"site"`

	// Банк — для счёта на оплату.
	BankName        string `json:"bankName"`
	BankBIK         string `json:"bankBik"`
	BankAccount     string `json:"bankAccount"`     // расчётный счёт
	BankCorrAccount string `json:"bankCorrAccount"` // корреспондентский счёт

	// Кто подписывает документ и на каком основании.
	SignerName  string `json:"signerName"`
	SignerTitle string `json:"signerTitle"`

	// Приписки в документе: про налог и про условия — своими словами.
	TaxNote    string `json:"taxNote"`
	FooterNote string `json:"footerNote"`

	UpdatedAt string `json:"updatedAt"`
}

// DefaultCompany — заготовка для первого запуска. Пустые поля лучше выдуманных:
// человек сразу видит, что заполнить, и не ищет, откуда в счёте чужой ИНН.
// Подсказки про налог и условия оплаты — единственное, что имеет смысл
// предзаполнить: их пишут почти все и почти одинаково.
func DefaultCompany() Company {
	return Company{
		SignerTitle: "Индивидуальный предприниматель",
		TaxNote:     "НДС не облагается",
		FooterNote:  "Оплата в течение 5 банковских дней с даты выставления счёта.",
	}
}

// Filled отвечает, можно ли печатать документ. Без наименования и ИНН
// документ выйдет обезличенным, и клиенту его отдавать нельзя.
func (c Company) Filled() bool {
	return strings.TrimSpace(c.ShortName) != "" && strings.TrimSpace(c.INN) != ""
}

func (s *Store) ensureCompany(ctx context.Context) error {
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM company WHERE id = 1`).Scan(&count); err != nil {
		return fmt.Errorf("проверка реквизитов: %w", err)
	}
	if count > 0 {
		return nil
	}
	payload, err := toJSON(DefaultCompany())
	if err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO company (id, payload, updated_at) VALUES (1, ?, ?)`, payload, now()); err != nil {
		return fmt.Errorf("создание реквизитов: %w", err)
	}
	return nil
}

func (s *Store) Company(ctx context.Context) (Company, error) {
	var payload, updatedAt string
	err := s.db.QueryRowContext(ctx,
		`SELECT payload, updated_at FROM company WHERE id = 1`).Scan(&payload, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Company{}, ErrNotFound
	}
	if err != nil {
		return Company{}, err
	}

	// Начинаем с заготовки: у сохранённого раньше JSON не будет новых полей,
	// и без этого они приехали бы пустыми вместо подсказок.
	company := DefaultCompany()
	if err := json.Unmarshal([]byte(payload), &company); err != nil {
		return Company{}, fmt.Errorf("разбор реквизитов: %w", err)
	}
	company.UpdatedAt = updatedAt
	return company, nil
}

func (s *Store) SaveCompany(ctx context.Context, company Company) (Company, error) {
	// Время правки ведём сами — присланное клиентом значение не в счёт.
	company.UpdatedAt = ""
	payload, err := toJSON(company)
	if err != nil {
		return Company{}, err
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE company SET payload = ?, updated_at = ? WHERE id = 1`, payload, now()); err != nil {
		return Company{}, fmt.Errorf("сохранение реквизитов: %w", err)
	}
	s.AddAudit(ctx, "company", 1, "update", strings.TrimSpace(company.ShortName))
	return s.Company(ctx)
}
