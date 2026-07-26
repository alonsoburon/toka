package handler

func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
