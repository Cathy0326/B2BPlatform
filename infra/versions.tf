terraform {
  required_version = ">= 1.6"

  required_providers {
    linode = {
      source  = "linode/linode"
      version = "~> 3.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }

  # Team setup: keep state remotely (e.g. Linode Object Storage via the s3 backend) with locking,
  # never in Git. Local state is fine for a personal demo.
  # backend "s3" { ... }
}

provider "linode" {
  token = var.linode_token
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
