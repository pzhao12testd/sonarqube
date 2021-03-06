CREATE TABLE "USER_ROLES" (
  "ID" INTEGER NOT NULL GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
  "USER_ID" INTEGER,
  "RESOURCE_ID" INTEGER,
  "ROLE" VARCHAR(64) NOT NULL
);

CREATE INDEX "USER_ROLES_RESOURCE" ON "USER_ROLES" ("RESOURCE_ID");

CREATE INDEX "USER_ROLES_USER" ON "USER_ROLES" ("USER_ID");
