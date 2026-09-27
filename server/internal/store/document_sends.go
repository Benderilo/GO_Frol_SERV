package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// Ответы клиента на документ, отправленный в MAX.
const (
	ReplyAccepted = "accepted" // согласен
	ReplyDeclined = "declined" // не устраивает
	ReplyQuestion = "question" // есть вопрос
)

// DocumentSend — документ, отправленный клиенту в MAX, и что тот ответил.
type DocumentSend struct {
	ID         int64  `json:"id"`
	DocumentID int64  `json:"documentId"`
	MaxUserID  int64  `json:"-"`
	SentAt     string `json:"sentAt"`
	Reply      string `json:"reply"` // пусто — ещё не ответил
	Note       string `json:"note"`  // причина отказа или вопрос
	RepliedAt  string `json:"repliedAt"`
}

// ErrNoMaxContact — клиент ещё не писал боту: отправить ему некуда.
var ErrNoMaxContact = errors.New("клиент ещё не писал боту в MAX")

// MaxUserForClient ищет клиента в MAX по его номеру: среди тех, кто
// поделился телефоном с ботом, и среди заявок из MAX. Бот может написать
// только тому, кто сам начинал с ним диалог.
func (s *Store) MaxUserForClient(ctx context.Context, clientID int64) (int64, error) {
	client, err := s.Client(ctx, clientID)
	if err != nil {
		return 0, err
	}
	digits := onlyDigits(client.Phone)
	if digits == "" {
		return 0, ErrNoMaxContact
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT user_id, phone FROM (
		    SELECT user_id, phone, updated_at AS at FROM max_dialogs WHERE phone != ''
		    UNION ALL
		    SELECT max_user_id, phone, created_at FROM requests WHERE max_user_id != 0
		 ) ORDER BY at DESC`)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var phone string
		if err := rows.Scan(&id, &phone); err != nil {
			return 0, err
		}
		if phoneMatches(phone, digits) {
			return id, nil
		}
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}
	return 0, ErrNoMaxContact
}

// AddDocumentSend отмечает, что документ ушёл клиенту.
func (s *Store) AddDocumentSend(ctx context.Context, documentID, maxUserID int64) (DocumentSend, error) {
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO document_sends (document_id, max_user_id, sent_at) VALUES (?, ?, ?)`,
		documentID, maxUserID, now())
	if err != nil {
		return DocumentSend{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return DocumentSend{}, err
	}
	s.AddAudit(ctx, "document", documentID, "send_max", "")
	return s.documentSend(ctx, id)
}

// DeleteDocumentSend убирает отметку об отправке, которая не дошла.
func (s *Store) DeleteDocumentSend(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM document_sends WHERE id = ?`, id)
	return err
}

// LastDocumentSend — последняя отправка документа; nil — не отправляли.
func (s *Store) LastDocumentSend(ctx context.Context, documentID int64) (*DocumentSend, error) {
	var id int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id FROM document_sends WHERE document_id = ? ORDER BY id DESC LIMIT 1`, documentID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	d, err := s.documentSend(ctx, id)
	return &d, err
}

func (s *Store) documentSend(ctx context.Context, id int64) (DocumentSend, error) {
	var d DocumentSend
	err := s.db.QueryRowContext(ctx,
		`SELECT id, document_id, max_user_id, sent_at, reply, note, replied_at
		 FROM document_sends WHERE id = ?`, id).
		Scan(&d.ID, &d.DocumentID, &d.MaxUserID, &d.SentAt, &d.Reply, &d.Note, &d.RepliedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return DocumentSend{}, ErrNotFound
	}
	return d, err
}

// DocumentReply записывает ответ клиента и ставит задачу — чтобы ответ
// не потерялся, пока в приложение никто не заглядывал. Ответить может
// только тот, кому документ отправляли. Повторный ответ заменяет прежний:
// клиент вправе передумать.
func (s *Store) DocumentReply(ctx context.Context, sendID, maxUserID int64, reply, note string) (DocumentSend, Document, error) {
	send, err := s.documentSend(ctx, sendID)
	if err != nil {
		return DocumentSend{}, Document{}, err
	}
	if send.MaxUserID != maxUserID {
		return DocumentSend{}, Document{}, ErrNotFound
	}
	doc, err := s.DocumentByID(ctx, send.DocumentID)
	if err != nil {
		return DocumentSend{}, Document{}, err
	}
	note = strings.TrimSpace(note)
	if _, err := s.db.ExecContext(ctx,
		`UPDATE document_sends SET reply = ?, note = ?, replied_at = ? WHERE id = ?`,
		reply, note, now(), sendID); err != nil {
		return DocumentSend{}, Document{}, err
	}

	var title, priority string
	name := DocumentName(doc)
	switch reply {
	case ReplyAccepted:
		title, priority = "Клиент согласен: "+name, "normal"
	case ReplyDeclined:
		title, priority = "Клиента не устраивает: "+name, "high"
	default:
		title, priority = "Вопрос клиента по документу: "+name, "high"
	}
	task, err := s.CreateTask(ctx, Task{
		ClientID: doc.ClientID,
		OrderID:  doc.OrderID,
		Title:    title,
		Note:     note,
		Priority: priority,
		DueDate:  now()[:10],
	})
	if err != nil {
		return DocumentSend{}, Document{}, err
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE document_sends SET task_id = ? WHERE id = ?`, task.ID, sendID); err != nil {
		return DocumentSend{}, Document{}, err
	}
	s.AddAudit(ctx, "document", doc.ID, "max_reply", reply)
	send, err = s.documentSend(ctx, sendID)
	return send, doc, err
}

// DocumentNote дописывает причину отказа или вопрос, присланные следом за
// нажатием кнопки, — и в ответ, и в задачу мастеру.
func (s *Store) DocumentNote(ctx context.Context, sendID, maxUserID int64, note string) (DocumentSend, error) {
	send, err := s.documentSend(ctx, sendID)
	if err != nil {
		return DocumentSend{}, err
	}
	if send.MaxUserID != maxUserID {
		return DocumentSend{}, ErrNotFound
	}
	note = strings.TrimSpace(note)
	if _, err := s.db.ExecContext(ctx,
		`UPDATE document_sends SET note = ? WHERE id = ?`, note, sendID); err != nil {
		return DocumentSend{}, err
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE tasks SET note = ?, updated_at = ?
		 WHERE id = (SELECT task_id FROM document_sends WHERE id = ?)`, note, now(), sendID); err != nil {
		return DocumentSend{}, err
	}
	return s.documentSend(ctx, sendID)
}

// DocumentName — «Счёт на оплату № 12 на 15 000,00 ₽» для сообщений и задач.
func DocumentName(d Document) string {
	name := d.KindTitle
	if name == "" {
		name = DocKindTitle(d.Kind)
	}
	if d.Number > 0 {
		name += fmt.Sprintf(" № %d", d.Number)
	}
	if d.TotalKop > 0 {
		name += " на " + formatRub(d.TotalKop)
	}
	return name
}

// formatRub — сумма для сообщений: «15 000 ₽» или «1 200,50 ₽».
func formatRub(kop int64) string {
	rub, k := kop/100, kop%100
	s := fmt.Sprint(rub)
	var b strings.Builder
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteRune(' ')
		}
		b.WriteRune(c)
	}
	if k != 0 {
		return fmt.Sprintf("%s,%02d ₽", b.String(), k)
	}
	return b.String() + " ₽"
}
