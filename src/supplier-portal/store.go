package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
	"time"
)

var opaqueID = regexp.MustCompile("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
var hexDigest = regexp.MustCompile("^[a-f0-9]{64}$")
var credential = regexp.MustCompile("^[A-Za-z0-9_-]{43}$")
var errUnavailable = errors.New("invitation unavailable")
var errConflict = errors.New("invitation conflict")
var errIncomplete = errors.New("questionnaire incomplete")

const maxBody = 8 << 20

type question struct {
	Key  string `json:"key"`
	Text string `json:"text"`
}

type questionnaire struct {
	SchemaVersion  int        `json:"schemaVersion"`
	InvitationID   string     `json:"invitationId"`
	Generation     int64      `json:"generation"`
	RequesterLabel string     `json:"requesterLabel"`
	ServiceScope   string     `json:"serviceScope"`
	Deadline       time.Time  `json:"deadline"`
	SnapshotHash   string     `json:"snapshotHash"`
	Questions      []question `json:"questions"`
}

type provision struct {
	Questionnaire        questionnaire `json:"questionnaire"`
	Verifier             string        `json:"verifier"`
	PreviousInvitationID string        `json:"previousInvitationId"`
	RetainDraft          bool          `json:"retainDraft"`
	ExpiresAt            time.Time     `json:"expiresAt"`
}

type answer struct {
	QuestionKey string `json:"questionKey"`
	Answer      string `json:"answer"`
	Comment     string `json:"comment"`
}

type submission struct {
	SchemaVersion int       `json:"schemaVersion"`
	InvitationID  string    `json:"invitationId"`
	Generation    int64     `json:"generation"`
	SnapshotHash  string    `json:"snapshotHash"`
	SubmissionID  string    `json:"submissionId"`
	SubmittedAt   time.Time `json:"submittedAt"`
	Answers       []answer  `json:"answers"`
}

type invitation struct {
	Provision  provision
	Revoked    bool
	Revision   uint64
	Draft      []answer
	Submission *submission
}

type store struct {
	dir  string
	lock *os.File
	mu   sync.Mutex
}

func openStore(dir string) (*store, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	info, err := os.Stat(dir)
	if err != nil {
		return nil, err
	}
	if info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("state directory must be private")
	}
	lock, err := os.OpenFile(filepath.Join(dir, ".lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = lock.Close()
		return nil, errors.New("state directory already in use")
	}
	return &store{dir: dir, lock: lock}, nil
}

// All record reads and writes are serialized in one process. Records are loaded
// by exact ID, so work does not grow with the number of historical invitations.
func (s *store) load(id string) (invitation, error) {
	if !opaqueID.MatchString(id) {
		return invitation{}, errUnavailable
	}
	f, err := os.Open(filepath.Join(s.dir, id+".json"))
	if errors.Is(err, os.ErrNotExist) {
		return invitation{}, errUnavailable
	}
	if err != nil {
		return invitation{}, err
	}
	defer f.Close()
	var record invitation
	err = json.NewDecoder(io.LimitReader(f, 2*maxBody)).Decode(&record)
	return record, err
}

func (s *store) save(id string, record invitation) error {
	f, err := os.CreateTemp(s.dir, ".pending-")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if err := json.NewEncoder(f).Encode(record); err != nil {
		return err
	}
	if err := f.Sync(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(f.Name(), filepath.Join(s.dir, id+".json")); err != nil {
		return err
	}
	dir, err := os.Open(s.dir)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func randomValues() (string, string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", "", err
	}
	secret := base64.RawURLEncoding.EncodeToString(raw[:])
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	id := fmt.Sprintf("%x-%x-%x-%x-%x", raw[0:4], raw[4:6], raw[6:8], raw[8:10], raw[10:16])
	return id, secret, nil
}
