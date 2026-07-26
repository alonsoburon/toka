package model

import "time"

type TaskStatus string

const (
	StatusPending TaskStatus = "pending"
	StatusDone    TaskStatus = "done"
	StatusSkipped TaskStatus = "skipped"
)

type Household struct {
	ID         int64     `json:"id"`
	Name       string    `json:"name"`
	InviteCode string    `json:"invite_code"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
	CreatedBy  int64     `json:"created_by"`
	UpdatedBy  int64     `json:"updated_by"`
}

type Person struct {
	ID          int64     `json:"id"`
	HouseholdID int64     `json:"household_id"`
	Name        string    `json:"name"`
	Color       string    `json:"color"`
	AvatarEmoji string    `json:"avatar_emoji"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	CreatedBy   int64     `json:"created_by"`
	UpdatedBy   int64     `json:"updated_by"`

	RowVersion int64   `json:"row_version"`
	ClientID   *string `json:"client_id,omitempty"`
}

type TaskTemplate struct {
	ID                  int64     `json:"id"`
	HouseholdID         int64     `json:"household_id"`
	Name                string    `json:"name"`
	Description         *string   `json:"description"`
	RecurrenceDays      *int      `json:"recurrence_days"`
	PreferredAssigneeID *int64    `json:"preferred_assignee_id"`
	IsActive            bool      `json:"is_active"`
	CreatedAt           time.Time `json:"created_at"`
	UpdatedAt           time.Time `json:"updated_at"`
	CreatedBy           int64     `json:"created_by"`
	UpdatedBy           int64     `json:"updated_by"`

	RowVersion int64   `json:"row_version"`
	ClientID   *string `json:"client_id,omitempty"`
}

type TaskInstance struct {
	ID            int64      `json:"id"`
	TemplateID    int64      `json:"template_id"`
	HouseholdID   int64      `json:"household_id"`
	Status        TaskStatus `json:"status"`
	DueAt         time.Time  `json:"due_at"`
	AssignedToID  *int64     `json:"assigned_to_id"`
	CompletedByID *int64     `json:"completed_by_id"`
	CompletedAt   *time.Time `json:"completed_at"`
	Notes         *string    `json:"notes"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
	CreatedBy     int64      `json:"created_by"`
	UpdatedBy     int64      `json:"updated_by"`

	RowVersion int64   `json:"row_version"`
	ClientID   *string `json:"client_id,omitempty"`

	TemplateName    *string `json:"template_name,omitempty"`
	AssignedToName  *string `json:"assigned_to_name,omitempty"`
	AssignedToColor *string `json:"assigned_to_color,omitempty"`
	AssignedToEmoji *string `json:"assigned_to_emoji,omitempty"`
	CompletedByName *string `json:"completed_by_name,omitempty"`
}
