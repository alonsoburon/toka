package handler

func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func nullIfEmptyPtr(s *string) *string {
	if s == nil || *s == "" {
		return nil
	}
	return s
}
