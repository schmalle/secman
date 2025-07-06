Draft Draft Draft

The database secman can be created via the script <https://github.com/schmalle/secman/blob/main/scripts/install.sh>.

Then please create users (password is simply "password" needed e.g.

INSERT INTO secman.users (id, username, email, password_hash, created_at, updated_at) VALUES (1, 'adminuser', 'admin@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', '2025-05-30 12:57:15', '2025-05-31 11:21:42');
INSERT INTO secman.users (id, username, email, password_hash, created_at, updated_at) VALUES (2, 'normaluser', 'user@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', '2025-05-30 12:57:15', '2025-05-31 11:21:42');

For the moment secman only supports two roles adminuser and normaluser. More are planned to come.



