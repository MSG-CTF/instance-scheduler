ALTER TABLE challenge_instance ADD COLUMN startup_cpu_millicores integer;
ALTER TABLE challenge_instance ADD CONSTRAINT ck_startup_cpu_profile
CHECK (startup_cpu_millicores IS NULL OR
       (cpu_millicores > 0 AND startup_cpu_millicores > cpu_millicores AND startup_cpu_millicores <= 2000));
