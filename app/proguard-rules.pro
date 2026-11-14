# Preserve line numbers for crash diagnostics while hiding original file names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve generic type signatures and runtime annotations required by Retrofit & Moshi
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Retrofit service interface & DTOs used for GitHub Releases API
-keep,allowobfuscation interface com.tombytee.releasefetch.data.remote.GitHubApiService { *; }
-keepnames class com.tombytee.releasefetch.data.remote.GitHubOwnerDto
-keepnames class com.tombytee.releasefetch.data.remote.GitHubRepoDto
-keepnames class com.tombytee.releasefetch.data.remote.GitHubAssetDto
-keepnames class com.tombytee.releasefetch.data.remote.GitHubReleaseDto
-keepclassmembers class com.tombytee.releasefetch.data.remote.*Dto { *; }

# Moshi KSP-generated JsonAdapters looked up reflectively by class name
-keep class com.tombytee.releasefetch.data.remote.*JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-keep class com.tombytee.releasefetch.data.local.*JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-keep class com.tombytee.releasefetch.data.update.*JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-keepnames class com.tombytee.releasefetch.data.local.CachedRaw*
-keepclassmembers class com.tombytee.releasefetch.data.local.CachedRaw* { *; }
-keepnames class com.tombytee.releasefetch.data.update.*Dto
-keepclassmembers class com.tombytee.releasefetch.data.update.*Dto { *; }

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keepclassmembers class com.tombytee.releasefetch.data.local.SavedRepoEntity { *; }
-keepclassmembers class com.tombytee.releasefetch.data.local.CachedRepoPageEntity { *; }

# Keep ViewModel constructors used via ViewModelProvider.Factory
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
