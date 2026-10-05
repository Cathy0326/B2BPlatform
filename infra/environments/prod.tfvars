# Production.
#   tofu workspace select -or-create prod && tofu apply -var-file=environments/prod.tfvars
environment        = "prod"
cloudflare_zone_id = "your-zone-id"
domain             = "quipmarket.example.com"
region             = "us-ord"
node_type          = "g6-standard-2"
node_count_min     = 2
node_count_max     = 4
db_type            = "g6-standard-1"
db_allow_list      = []
ingress_ip         = ""
