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

  # State lives in Linode Object Storage (S3-compatible), never in Git and never on one laptop.
  # Each workspace (dev, staging, prod) gets its own state file: env/<workspace>/quipmarket.tfstate.
  # Credentials: an Object Storage access key in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
  # Applies run one at a time per environment (the deploy workflow uses a concurrency group);
  # OpenTofu's encryption keeps the database password in the state unreadable without the passphrase.
  backend "s3" {
    bucket                      = "quipmarket-tofu-state"
    key                         = "quipmarket.tfstate"
    workspace_key_prefix        = "env"
    region                      = "us-ord"
    endpoints                   = { s3 = "https://us-ord-1.linodeobjects.com" }
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
  }

  # State and plan files contain the database root password, so encrypt them client-side.
  # The passphrase comes from TF_VAR_state_passphrase (a GitHub environment secret in CI).
  encryption {
    key_provider "pbkdf2" "main" {
      passphrase = var.state_passphrase
    }
    method "aes_gcm" "main" {
      keys = key_provider.pbkdf2.main
    }
    state {
      method   = method.aes_gcm.main
      enforced = true
    }
    plan {
      method   = method.aes_gcm.main
      enforced = true
    }
  }
}

provider "linode" {
  token = var.linode_token
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
