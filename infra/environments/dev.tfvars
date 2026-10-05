# Development: smallest footprint. Non-secret values only; tokens come from TF_VAR_* environment variables.
#   tofu workspace select -or-create dev && tofu apply -var-file=environments/dev.tfvars
environment        = "dev"
cloudflare_zone_id = "your-zone-id"
domain             = "dev.quipmarket.example.com"
region             = "us-ord"
node_type          = "g6-standard-1"
node_count_min     = 1
node_count_max     = 2
db_type            = "g6-nanode-1"
db_allow_list      = []
ingress_ip         = ""
