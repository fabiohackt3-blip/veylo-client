using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Veylo.Launcher;

internal static class InstanceCover
{
    public static ImageSource Load(string root, Profile profile)
    {
        if (profile.CoverFile is { } filename)
        {
            try { return Decode(Path.Combine(root, "covers", filename)); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or NotSupportedException
                or FormatException) { }
        }

        var random = new Random(BitConverter.ToInt32(profile.Id.ToByteArray()));
        byte hue = (byte)random.Next(100, 220);
        var sky = new LinearGradientBrush(Color.FromRgb(16, 30, 67), Color.FromRgb(37, hue, 195), 90);
        var scenery = new DrawingGroup();
        using (var draw = scenery.Open())
        {
            draw.DrawRectangle(sky, null, new Rect(0, 0, 320, 180));
            var sun = new SolidColorBrush(Color.FromArgb(215, 224, 253, 255));
            draw.DrawEllipse(sun, null, new Point(random.Next(190, 270), random.Next(35, 68)), 13, 13);
            for (int layer = 0; layer < 3; layer++)
            {
                int height = 67 + 29 * layer;
                var face = new SolidColorBrush(Color.FromRgb((byte)(23 - layer * 4),
                    (byte)(80 + hue / 8 - 20 * layer), (byte)(109 - layer * 20)));
                var ridge = new SolidColorBrush(Color.FromRgb((byte)(44 - layer * 7),
                    (byte)(109 + hue / 8 - 22 * layer), (byte)(136 - layer * 22)));
                for (int x = 0; x < 320; x += 16)
                {
                    height = Math.Clamp(height + random.Next(-2, 3) * 8, 46 + layer * 29, 112 + layer * 24);
                    draw.DrawRectangle(face, null, new Rect(x, height, 16, 180 - height));
                    draw.DrawRectangle(ridge, null, new Rect(x, height, 16, 4));
                }
            }
        }
        var image = new DrawingImage(scenery);
        image.Freeze();
        return image;
    }

    public static string Save(string root, Profile profile, string source)
    {
        if (new FileInfo(source).Length > 20 * 1024 * 1024)
            throw new InvalidDataException("Das Bild darf höchstens 20 MB groß sein.");
        var image = Decode(source);
        if (image.PixelWidth < 1 || image.PixelHeight < 1 || image.PixelHeight > 4096)
            throw new InvalidDataException("Das Bild hat ungültige Abmessungen.");
        var folder = Path.Combine(root, "covers");
        Directory.CreateDirectory(folder);
        var filename = profile.Id.ToString("N") + ".png";
        var path = Path.Combine(folder, filename);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(image));
            using (var output = File.Create(temporary)) encoder.Save(output);
            File.Move(temporary, path, overwrite: true);
        }
        finally { File.Delete(temporary); }
        return filename;
    }

    private static BitmapImage Decode(string path)
    {
        using var stream = File.OpenRead(path);
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.DecodePixelWidth = 640;
        bitmap.StreamSource = stream;
        bitmap.EndInit();
        bitmap.Freeze();
        return bitmap;
    }
}
