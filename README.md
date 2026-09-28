# GPT File Bridge

Android Kotlin app for moving a file to a cloud URL when direct ChatGPT attachment upload is unavailable.

The picker accepts arbitrary files such as TXT, photos, videos, ZIP archives, PDFs, APKs and other file types.

Large uploads use Supabase Storage resumable TUS uploads in 6 MiB chunks and report progress.

After upload the app creates a public URL, a copy-ready prompt, and an Android share action. This does not bypass ChatGPT limits. It provides an alternate URL-based delivery path. Whether ChatGPT can fetch and process a specific URL or binary format depends on the tools available in that chat.

Storage uses the Supabase gpt-file-bridge bucket. The bucket is public for reads and allows anonymous inserts because this version intentionally has no account/login flow. The app uses a publishable client key, not a service-role key.

GitHub Actions builds the debug APK on every push to main.
