-- The identity that filed a request when a service or agent files it on behalf of a person. The person is the
-- requester (four-eyes applies to them); the filing identity may read the request it filed. NULL means the
-- requester filed it themselves.
ALTER TABLE approval_request ADD COLUMN filed_by VARCHAR(128);
CREATE INDEX idx_approval_filed_by ON approval_request (filed_by) WHERE filed_by IS NOT NULL;
